package com.example.tab_able.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.tab_able.logic.BalanceCalculator;
import com.example.tab_able.logic.Money;
import com.example.tab_able.logic.SplitCalculator;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TabRepository {
    private static TabRepository instance;

    public interface DataChangeListener {
        void onDataChanged();
    }

    public interface ChatMessageListener {
        void onMessagesUpdated(List<SpaceMessage> messages);
    }

    /** Reports whether the Chat screen may start writing to a space. */
    public interface SpaceReadyListener {
        void onSpaceReady(String spaceId);
        void onSpaceUnavailable(String reason);
    }

    private FirebaseFirestore firestore;
    private FirebaseUsers users;
    private final List<DataChangeListener> changeListeners = new ArrayList<>();

    // Cached live state
    private String currentUserId = "";
    private String currentUserName = "User";
    private String currentUserEmail = "";

    private final List<Person> people = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<Space> spaces = new ArrayList<>();
    private final List<String> recentActivities = new ArrayList<>();
    private final List<TransitRoute> transitRoutes = new ArrayList<>();

    private final List<BalanceCalculator.ExpenseInput> allSpaceExpenses = new ArrayList<>();
    private final List<BalanceCalculator.SettlementInput> allConfirmedSettlements = new ArrayList<>();

    private ListenerRegistration friendsReg;
    private ListenerRegistration spacesReg;
    private ListenerRegistration transitReg;
    private final List<ListenerRegistration> spaceSubRegs = new ArrayList<>();

    private final Map<String, String> userCache = new HashMap<>();

    /** friendUid -> the friends/{friendUid} document data, owned by this user. */
    private final Map<String, Map<String, Object>> friendEdges = new HashMap<>();
    /** friendUid -> resolved display name, once the profile has been fetched. */
    private final Map<String, String> friendNames = new HashMap<>();

    /** Latest known total spend per space, used for the Groups tab. */
    private final Map<String, Long> spaceTotals = new HashMap<>();

    public interface OnNameResolved {
        void onResolved(String name);
    }

    public static String getDirectSpaceId(String uid1, String uid2) {
        if (uid1 == null || uid2 == null) return "";
        if (uid1.compareTo(uid2) < 0) {
            return uid1 + "_" + uid2;
        } else {
            return uid2 + "_" + uid1;
        }
    }

    public void resolveUserName(String uid, OnNameResolved callback) {
        if (uid == null || uid.isEmpty()) {
            if (callback != null) callback.onResolved("User");
            return;
        }
        if (userCache.containsKey(uid)) {
            if (callback != null) callback.onResolved(userCache.get(uid));
            return;
        }
        // A friend edge already carries the name, and unlike a profile it is always
        // readable by us.
        String known = friendNames.get(uid);
        if (known != null && !known.isEmpty()) {
            userCache.put(uid, known);
            if (callback != null) callback.onResolved(known);
            return;
        }
        if (firestore != null) {
            firestore.collection("users").document(uid).get()
                    .addOnSuccessListener(doc -> {
                        String name = doc.getString("displayName");
                        if (name == null) name = doc.getString("username");
                        if (name == null) name = doc.getString("name");
                        if (name == null) name = uid;
                        userCache.put(uid, name);
                        if (callback != null) callback.onResolved(name);
                    })
                    .addOnFailureListener(e -> {
                        if (callback != null) callback.onResolved(uid);
                    });
        } else {
            if (callback != null) callback.onResolved(uid);
        }
    }

    public static class TransitRoute {
        public String from;
        public String to;
        public int fare;

        public TransitRoute() {}

        public TransitRoute(String from, String to, int fare) {
            this.from = from;
            this.to = to;
            this.fare = fare;
        }

        public String getFrom() { return from != null ? from : ""; }
        public String getTo() { return to != null ? to : ""; }
        public int getFare() { return fare; }
    }

    private TabRepository() {
        initFirestore();
    }

    public static synchronized TabRepository getInstance() {
        if (instance == null) {
            instance = new TabRepository();
        }
        return instance;
    }

    public void addDataChangeListener(DataChangeListener listener) {
        if (listener != null && !changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public void removeDataChangeListener(DataChangeListener listener) {
        changeListeners.remove(listener);
    }

    private void notifyListeners() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (DataChangeListener l : new ArrayList<>(changeListeners)) {
                try {
                    l.onDataChanged();
                } catch (Exception e) {
                    Log.e("TabRepository", "Error in DataChangeListener", e);
                }
            }
        });
    }

    public void setCurrentUser(String uid, String username) {
        setCurrentUser(uid, username, null);
    }

    /**
     * Sets the signed-in identity and starts every realtime listener.
     *
     * @param uid      Firebase Auth uid
     * @param username label to use until the real profile is read back
     * @param email    the account email, used to repair a missing profile document
     */
    public void setCurrentUser(String uid, String username, String email) {
        this.currentUserId = uid != null ? uid : "";
        this.currentUserName = username != null && !username.isEmpty() ? username : "User";
        this.currentUserEmail = email != null ? email : "";
        CurrentUser.setSession(this.currentUserId, username, this.currentUserName, this.currentUserEmail);

        if (firestore != null && !this.currentUserId.isEmpty()) {
            startRealtimeListeners();
        }

        // Note: the profile is NOT repaired here. At this point the only "username"
        // we have is a guess derived from the email address, and writing that would
        // overwrite the username the user actually chose. LoginActivity calls
        // syncProfile() once the real document has been read.
    }

    /**
     * Makes sure users/{uid} and both lookup keys exist, so an account is always
     * discoverable by email and username.
     *
     * Safe to call repeatedly, and safe for a returning user: it merges rather than
     * replaces, and only uses the username currently held in {@link CurrentUser},
     * which is populated from Firestore by {@code loadProfile}.
     */
    public void syncProfile() {
        if (users == null || currentUserId.isEmpty()) return;
        users.healProfile(currentUserId, currentUserEmail,
                CurrentUser.getDisplayName(), CurrentUser.getUsername());
    }

    /**
     * Cleans up lookup keys that point at accounts with no profile, so those keys
     * can be claimed again and a search for them stops leading to a dead end.
     *
     * The set of keys is remembered in users/{uid}/prefs/discovery, so this only
     * touches Firestore when the user's identity details actually change rather
     * than on every launch.
     */
    public void repairLookupIndex() {
        if (users == null || firestore == null || currentUserId.isEmpty()) return;

        final String emailKey = FirebaseUsers.normalise(currentUserEmail);
        final List<String> usernameKeys = new ArrayList<>();
        if (CurrentUser.getUsername() != null && !CurrentUser.getUsername().isEmpty()) {
            usernameKeys.add(FirebaseUsers.normalise(CurrentUser.getUsername()));
        }

        firestore.collection("users").document(currentUserId)
                .collection("prefs").document("discovery")
                .get()
                .addOnCompleteListener(task -> {
                    DocumentSnapshot doc = task.isSuccessful() ? task.getResult() : null;

                    List<String> seenEmails = stringList(doc, "emailKeys");
                    List<String> seenUsernames = stringList(doc, "usernameKeys");

                    final List<String> newEmails = new ArrayList<>();
                    if (!emailKey.isEmpty() && !seenEmails.contains(emailKey)) {
                        newEmails.add(emailKey);
                    }
                    final List<String> newUsernames = new ArrayList<>();
                    for (String k : usernameKeys) {
                        if (!seenUsernames.contains(k)) newUsernames.add(k);
                    }

                    if (newEmails.isEmpty() && newUsernames.isEmpty()) return;

                    Runnable record = () -> {
                        Map<String, Object> prefs = new HashMap<>();
                        List<String> allEmails = new ArrayList<>(seenEmails);
                        allEmails.addAll(newEmails);
                        List<String> allUsernames = new ArrayList<>(seenUsernames);
                        allUsernames.addAll(newUsernames);
                        prefs.put("emailKeys", allEmails);
                        prefs.put("usernameKeys", allUsernames);
                        prefs.put("checkedAt", System.currentTimeMillis());
                        firestore.collection("users").document(currentUserId)
                                .collection("prefs").document("discovery")
                                .set(prefs, SetOptions.merge());
                    };

                    users.dropOrphanedLookups(newEmails, "emailLookup", () ->
                            users.dropOrphanedLookups(newUsernames, "usernames", record));
                });
    }

    private static List<String> stringList(@Nullable DocumentSnapshot doc, String field) {
        List<String> out = new ArrayList<>();
        if (doc == null || !doc.exists()) return out;
        Object raw = doc.get(field);
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof String) out.add((String) item);
            }
        }
        return out;
    }

    /** Looks up a profile by email or username — used by the add-friend search. */
    public void searchUsers(String query, FirebaseUsers.UserLookup callback) {
        if (users == null) {
            callback.onError("Firestore is not available on this device");
            return;
        }
        users.findUserByQuery(query, callback);
    }

    public FirebaseUsers getUsersApi() {
        return users;
    }

    public void clearData() {
        if (friendsReg != null) { friendsReg.remove(); friendsReg = null; }
        if (spacesReg != null) { spacesReg.remove(); spacesReg = null; }
        if (transitReg != null) { transitReg.remove(); transitReg = null; }
        clearSpaceSubRegs();

        people.clear();
        groups.clear();
        spaces.clear();
        recentActivities.clear();
        transitRoutes.clear();
        allSpaceExpenses.clear();
        allConfirmedSettlements.clear();
        friendEdges.clear();
        friendNames.clear();
        userCache.clear();
        currentUserId = "";
        currentUserName = "User";
        currentUserEmail = "";
        changeListeners.clear();
        CurrentUser.clear();
    }

    private void clearSpaceSubRegs() {
        for (ListenerRegistration reg : spaceSubRegs) {
            if (reg != null) reg.remove();
        }
        spaceSubRegs.clear();
    }

    public String getCurrentUserId() { return currentUserId; }
    public String getCurrentUserName() { return currentUserName; }
    public String getCurrentUserEmail() { return currentUserEmail; }

    private void initFirestore() {
        try {
            firestore = FirebaseFirestore.getInstance();
            users = new FirebaseUsers(firestore);
        } catch (Exception ignored) {
            firestore = null;
            users = null;
        }
    }

    private void startRealtimeListeners() {
        if (firestore == null || currentUserId.isEmpty()) return;

        // 1. Real friendship edges: users/{currentUserId}/friends
        friendsReg = firestore.collection("users").document(currentUserId).collection("friends")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;

                    friendEdges.clear();
                    List<String> friendUids = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        String fUid = doc.getString("friendUid");
                        if (fUid == null) fUid = doc.getId();
                        if (fUid == null || fUid.isEmpty()) continue;
                        friendEdges.put(fUid, doc.getData());
                        friendUids.add(fUid);
                    }
                    fetchFriendsDetails(friendUids);
                });

        // 2. Scoped Listen to Spaces where memberUids contains currentUserId
        spacesReg = firestore.collection("spaces")
                .whereArrayContains("memberUids", currentUserId)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    spaces.clear();
                    groups.clear();
                    clearSpaceSubRegs();

                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        Space sp = doc.toObject(Space.class);
                        if (sp != null) {
                            spaces.add(sp);
                            if ("GROUP".equalsIgnoreCase(sp.getType())) {
                                groups.add(new Group(sp.getId(), sp.getName(), "🏔️", 0, 0, sp.getMemberUids()));
                            }
                            listenToSpaceSubcollections(sp.getId());
                        }
                    }
                    notifyListeners();
                    recalculateAllBalances();
                });

        // 3. Listen to Transit Routes
        transitReg = firestore.collection("transit_routes").addSnapshotListener((snapshots, e) -> {
            if (e != null || snapshots == null) return;
            transitRoutes.clear();
            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                TransitRoute tr = doc.toObject(TransitRoute.class);
                if (tr != null) transitRoutes.add(tr);
            }
            notifyListeners();
        });
    }

    private void listenToSpaceSubcollections(String spaceId) {
        if (firestore == null) return;

        ListenerRegistration expReg = firestore.collection("spaces").document(spaceId).collection("expenses")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    recalculateAllBalances();
                });
        spaceSubRegs.add(expReg);

        ListenerRegistration stReg = firestore.collection("spaces").document(spaceId).collection("settlements")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    recalculateAllBalances();
                });
        spaceSubRegs.add(stReg);
    }

    private void recalculateAllBalances() {
        if (firestore == null || currentUserId.isEmpty()) return;

        allSpaceExpenses.clear();
        allConfirmedSettlements.clear();
        recentActivities.clear();
        spaceTotals.clear();

        if (spaces.isEmpty()) {
            updatePersonBalances();
            return;
        }

        final int[] pendingSpaces = {spaces.size()};

        for (Space sp : spaces) {
            String spaceId = sp.getId();

            firestore.collection("spaces").document(spaceId).collection("expenses").get()
                    .addOnCompleteListener(expTask -> {
                        long spentPaise = 0;
                        if (expTask.isSuccessful() && expTask.getResult() != null) {
                            for (DocumentSnapshot edoc : expTask.getResult().getDocuments()) {
                                SpaceExpense sexp = edoc.toObject(SpaceExpense.class);
                                if (sexp == null) continue;
                                String payer = sexp.getPaidByUid();
                                long amount = sexp.getAmountPaise();
                                spentPaise += amount;

                                Map<String, Long> shares = sexp.getShares();
                                if (shares == null || shares.isEmpty()) {
                                    shares = new HashMap<>();
                                    List<String> splits = sp.getMemberUids();
                                    long perPerson = amount / (splits.isEmpty() ? 1 : splits.size());
                                    for (String u : splits) shares.put(u, perPerson);
                                }
                                allSpaceExpenses.add(new BalanceCalculator.ExpenseInput(payer, shares));
                                recentActivities.add("Expense: " + sexp.getTitle() + " · ₹" + (amount / 100));
                            }
                        }
                        spaceTotals.put(spaceId, spentPaise);

                        firestore.collection("spaces").document(spaceId).collection("settlements").get()
                                .addOnCompleteListener(stTask -> {
                                    if (stTask.isSuccessful() && stTask.getResult() != null) {
                                        for (DocumentSnapshot sdoc : stTask.getResult().getDocuments()) {
                                            SpaceSettlement st = sdoc.toObject(SpaceSettlement.class);
                                            if (st != null && "CONFIRMED".equalsIgnoreCase(st.getStatus())) {
                                                allConfirmedSettlements.add(new BalanceCalculator.SettlementInput(
                                                        st.getFromUid(), st.getToUid(), st.getAmountPaise()));
                                            }
                                        }
                                    }
                                    if (--pendingSpaces[0] <= 0) {
                                        applyGroupTotals();
                                        updatePersonBalances();
                                    }
                                });
                    });
        }
    }

    private void applyGroupTotals() {
        for (Group g : groups) {
            Long total = spaceTotals.get(g.getId());
            g.setTotalSpent(total != null ? (int) (total / 100) : 0);
        }
    }

    private void updatePersonBalances() {
        for (Person p : people) {
            String fUid = p.getId();
            // computePairwiseBalance returns PAISE. Person.netAmount is stored in
            // RUPEES (see Person), which is what every UI site expects, so convert
            // exactly once here rather than leaving each screen to guess.
            long balancePaise = BalanceCalculator.computePairwiseBalance(
                    currentUserId, fUid, allSpaceExpenses, allConfirmedSettlements);
            p.setNetAmount((int) (balancePaise / 100));

            if (balancePaise == 0) {
                p.setTag("all square ✨");
            } else if (balancePaise > 0) {
                p.setTag("owes you");
            } else {
                p.setTag("you owe");
            }
        }
        notifyListeners();
    }

    /**
     * Builds the people list from real profiles.
     *
     * Profiles are private: they are readable only by the owner and by people who
     * are friends or have a request in flight. So the friend's display name is
     * carried on the friendship edge itself (written when the request was
     * accepted), and the profile fetch is only an enhancement that fills in the
     * email. If that read is denied the person still appears, correctly named.
     */
    private void fetchFriendsDetails(List<String> friendUids) {
        if (friendUids.isEmpty()) {
            people.clear();
            updatePersonBalances();
            return;
        }

        final int total = friendUids.size();
        final List<Person> resolved = new ArrayList<>();
        final int[] pending = {total};

        for (String fUid : friendUids) {
            final String friendUid = fUid;
            final Map<String, Object> edge = friendEdges.get(friendUid);
            final String edgeName = edgeNameFrom(edge);

            Person seeded = new Person(friendUid, edgeName, "🙂", 0, "", "");
            if (firestore == null) {
                synchronized (resolved) { resolved.add(seeded); }
                if (--pending[0] <= 0) finishFriends(resolved);
                continue;
            }

            firestore.collection("users").document(friendUid).get()
                    .addOnCompleteListener(task -> {
                        Person p = null;
                        if (task.isSuccessful() && task.getResult() != null
                                && task.getResult().exists()) {
                            p = personFromProfile(friendUid, task.getResult());
                        }
                        if (p == null) {
                            // Denied or missing: fall back to the name on the edge.
                            p = seeded;
                        }
                        if ((p.getName() == null || p.getName().isEmpty()
                                || "TABS user".equals(p.getName())) && !edgeName.isEmpty()) {
                            p.setName(edgeName);
                        }
                        friendNames.put(friendUid, p.getName());
                        synchronized (resolved) { resolved.add(p); }

                        if (--pending[0] <= 0) finishFriends(resolved);
                    });
        }
    }

    /** Best display name available on a friendship edge document. */
    private static String edgeNameFrom(Map<String, Object> edge) {
        if (edge == null) return "";
        Object name = edge.get("friendName");
        return name instanceof String ? (String) name : "";
    }

    private void finishFriends(List<Person> resolved) {
        synchronized (resolved) {
            java.util.Collections.sort(resolved,
                    (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            people.clear();
            people.addAll(resolved);
        }
        updatePersonBalances();
    }

    /** Maps a users/{uid} document onto the UI’s Person model. */
    private Person personFromProfile(String uid, DocumentSnapshot doc) {
        String name = doc.getString("displayName");
        if (name == null || name.isEmpty()) name = doc.getString("username");
        if (name == null || name.isEmpty()) name = "TABS user";
        String email = doc.getString("email");
        String emoji = doc.getString("emoji");
        if (emoji == null || emoji.isEmpty()) emoji = "🙂";

        userCache.put(uid, name);
        friendNames.put(uid, name);
        return new Person(uid, name, emoji, 0, "", email != null ? email : "");
    }

    public List<Person> getAllPeople() { return new ArrayList<>(people); }

    public List<Person> getPeopleOwedToYou() {
        List<Person> res = new ArrayList<>();
        for (Person p : people) {
            if (p.getNetAmount() > 0) res.add(p);
        }
        return res;
    }

    public List<Person> getPeopleYouOwe() {
        List<Person> res = new ArrayList<>();
        for (Person p : people) {
            if (p.getNetAmount() < 0) res.add(p);
        }
        return res;
    }

    public Person getPersonById(String id) {
        for (Person p : people) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    public Group getGroupById(String groupId) {
        for (Group g : groups) {
            if (g.getId().equals(groupId)) return g;
        }
        return null;
    }

    public int getTotalOwedToYou() {
        // Person net amounts are already rupees, so sum them directly. Dividing here
        // as well would show a total 100x too small.
        int sum = 0;
        for (Person p : people) {
            if (p.getNetAmount() > 0) sum += p.getNetAmount();
        }
        return sum;
    }

    public int getTotalYouOwe() {
        int sum = 0;
        for (Person p : people) {
            if (p.getNetAmount() < 0) sum += Math.abs(p.getNetAmount());
        }
        return sum;
    }

    public List<Group> getGroups() { return new ArrayList<>(groups); }
    public List<Space> getSpaces() { return new ArrayList<>(spaces); }

    /**
     * Real ledger for a 1-to-1 tab: every expense in the DIRECT space between the
     * two people, plus every confirmed settlement, newest first.
     */
    public void loadLedgerForPerson(String personId, LedgerListener listener) {
        if (firestore == null || currentUserId.isEmpty() || personId == null || personId.isEmpty()) {
            if (listener != null) listener.onLedgerReady(new ArrayList<>());
            return;
        }

        final String spaceId = getDirectSpaceId(currentUserId, personId);
        final List<LedgerItem> items = new ArrayList<>();

        firestore.collection("spaces").document(spaceId).collection("expenses").get()
                .addOnCompleteListener(expTask -> {
                    if (expTask.isSuccessful() && expTask.getResult() != null) {
                        for (DocumentSnapshot doc : expTask.getResult().getDocuments()) {
                            SpaceExpense e = doc.toObject(SpaceExpense.class);
                            if (e == null) continue;

                            Map<String, Long> shares = e.getShares();
                            int myShare = 0;
                            if (shares != null && shares.get(currentUserId) != null) {
                                myShare = (int) (shares.get(currentUserId) / 100);
                            }
                            boolean paidByMe = currentUserId.equals(e.getPaidByUid());

                            items.add(new LedgerItem(
                                    doc.getId(),
                                    personId,
                                    formatDate(e.getOccurredAt()),
                                    e.getTitle(),
                                    paidByMe ? "you" : nameFor(personId),
                                    (int) (e.getAmountPaise() / 100),
                                    myShare));
                        }
                    }

                    firestore.collection("spaces").document(spaceId).collection("settlements").get()
                            .addOnCompleteListener(stTask -> {
                                if (stTask.isSuccessful() && stTask.getResult() != null) {
                                    for (DocumentSnapshot doc : stTask.getResult().getDocuments()) {
                                        SpaceSettlement s = doc.toObject(SpaceSettlement.class);
                                        if (s == null || !"CONFIRMED".equalsIgnoreCase(s.getStatus())) continue;
                                        items.add(new LedgerItem(
                                                doc.getId(),
                                                personId,
                                                formatDate(s.getCreatedAt()),
                                                (int) (s.getAmountPaise() / 100)));
                                    }
                                }
                                if (listener != null) listener.onLedgerReady(items);
                            });
                });
    }

    public interface LedgerListener {
        void onLedgerReady(List<LedgerItem> items);
    }

    private static String formatDate(long epochMillis) {
        if (epochMillis <= 0) return "";
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                .format(new java.util.Date(epochMillis));
    }

    public List<LedgerItem> getLedgerForPerson(String personId) {
        return new ArrayList<>();
    }

    public List<String> getRecentActivities() { return new ArrayList<>(recentActivities); }

    public void resolveDispute(String groupId, String disputeId) {
        if (firestore != null) {
            firestore.collection("disputes").document(disputeId).delete();
        }
    }

    /**
     * Creates a GROUP space. Accepts a mix of uids and typed usernames: any entry
     * that is not a known uid is resolved through the usernames lookup, so a user
     * can start a group by typing the usernames of people they have added.
     */
    public void createGroup(String name, String emoji, List<String> memberKeys) {
        if (firestore == null || currentUserId.isEmpty()) return;

        final List<String> members = new ArrayList<>();
        members.add(currentUserId);

        final List<String> unresolved = new ArrayList<>();
        for (String key : memberKeys) {
            if (key == null || key.trim().isEmpty()) continue;
            String k = key.trim();
            if (members.contains(k)) continue;
            if (isKnownUid(k)) {
                members.add(k);
            } else {
                unresolved.add(k);
            }
        }

        if (unresolved.isEmpty()) {
            writeGroup(name, emoji, members);
            return;
        }

        final int[] pending = {unresolved.size()};
        final List<String> resolvedUids = new ArrayList<>();
        for (String key : unresolved) {
            users.findUserByQuery(key, new FirebaseUsers.UserLookup() {
                @Override
                public void onFound(String uid) {
                    synchronized (resolvedUids) {
                        resolvedUids.add(uid);
                    }
                    finish();
                }

                @Override
                public void onNotFound() {
                    finish();
                }

                @Override
                public void onError(String message) {
                    finish();
                }

                private void finish() {
                    if (--pending[0] <= 0) {
                        synchronized (resolvedUids) {
                            for (String u : resolvedUids) {
                                if (!members.contains(u)) members.add(u);
                            }
                        }
                        writeGroup(name, emoji, members);
                    }
                }
            });
        }
    }

    private void writeGroup(String name, String emoji, List<String> members) {
        String id = firestore.collection("spaces").document().getId();
        Space space = new Space(id, "GROUP", members, currentUserId,
                name, "", "", System.currentTimeMillis());
        space.setEmoji(emoji);
        firestore.collection("spaces").document(id).set(space)
                .addOnFailureListener(e ->
                        android.util.Log.e("TabRepository", "Could not create group", e));
    }

    private boolean isKnownUid(String key) {
        for (Person p : people) {
            if (p.getId().equals(key)) return true;
        }
        return friendEdges.containsKey(key) || friendNames.containsKey(key);
    }

    /**
     * Guarantees the DIRECT space between this user and one other person exists,
     * then reports the id back.
     *
     * Chat cannot work without this: messages, expenses and settlements all live
     * under a space document, and the security rules only allow them when the
     * writer is listed in that space's memberUids. Creating it is idempotent, so
     * both participants racing to open the chat is harmless.
     */
    public void ensureDirectSpace(String otherUid, SpaceReadyListener listener) {
        if (firestore == null || currentUserId.isEmpty()) {
            listener.onSpaceUnavailable("You are not signed in");
            return;
        }
        if (otherUid == null || otherUid.isEmpty() || otherUid.equals(currentUserId)) {
            listener.onSpaceUnavailable("This contact is not a real TABS user yet");
            return;
        }

        final String spaceId = getDirectSpaceId(currentUserId, otherUid);
        DocumentReference ref = firestore.collection("spaces").document(spaceId);

        ref.get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                listener.onSpaceReady(spaceId);
                return;
            }

            List<String> members = new ArrayList<>();
            members.add(currentUserId);
            members.add(otherUid);

            Space direct = new Space(spaceId, "DIRECT", members, currentUserId,
                    "Direct Tab", "", "", System.currentTimeMillis());

            // merge() so a concurrently created space keeps its name/emoji.
            ref.set(direct, SetOptions.merge())
                    .addOnSuccessListener(unused -> listener.onSpaceReady(spaceId))
                    .addOnFailureListener(e -> listener.onSpaceUnavailable(
                            e.getMessage() != null ? e.getMessage() : "Could not open this chat"));
        });
    }

    /** The DIRECT space id for a person, whether or not it exists yet. */
    public String directSpaceIdFor(String otherUid) {
        if (otherUid == null) return "";
        return getDirectSpaceId(currentUserId, otherUid);
    }

    public ListenerRegistration listenToChat(String spaceId, ChatMessageListener listener) {
        if (firestore == null || spaceId == null || spaceId.isEmpty()) return null;
        return firestore.collection("spaces").document(spaceId).collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    List<SpaceMessage> list = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        SpaceMessage cm = messageFromDocument(doc);
                        if (cm != null) list.add(cm);
                    }
                    if (listener != null) listener.onMessagesUpdated(list);
                });
    }

    /**
     * Reads a message document by hand rather than via toObject().
     *
     * New messages store timestamp as a Firestore server timestamp, which is a
     * Timestamp object and cannot be deserialised into the model's long field —
     * toObject() would throw and the whole message would be dropped from the
     * chat. Parsing field by field keeps old messages (plain epoch millis) and
     * new ones both readable.
     */
    private SpaceMessage messageFromDocument(DocumentSnapshot doc) {
        try {
            SpaceMessage msg = new SpaceMessage();
            msg.setId(doc.getId());
            msg.setSenderUid(doc.getString("senderUid"));
            msg.setSenderName(doc.getString("senderName"));
            msg.setText(doc.getString("text"));
            msg.setType(doc.getString("type"));
            msg.setStatus(doc.getString("status"));
            msg.setReferenceId(doc.getString("referenceId"));

            Long amount = doc.getLong("amount");
            msg.setAmount(amount != null ? amount.intValue() : 0);

            msg.setTimestamp(readTimestamp(doc, "timestamp"));
            return msg;
        } catch (Exception e) {
            android.util.Log.e("TabRepository", "Skipping unreadable message " + doc.getId(), e);
            return null;
        }
    }

    /** Accepts both a server Timestamp and a legacy numeric epoch value. */
    static long readTimestamp(DocumentSnapshot doc, String field) {
        Object raw = doc.get(field);
        if (raw instanceof com.google.firebase.Timestamp) {
            return ((com.google.firebase.Timestamp) raw).toDate().getTime();
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        return 0L;
    }

    /** Read-only view of the messages in a space, used by the ledger screen. */
    public void loadMessages(String spaceId, ChatMessageListener listener) {
        if (firestore == null || spaceId == null || spaceId.isEmpty()) {
            if (listener != null) listener.onMessagesUpdated(new ArrayList<>());
            return;
        }
        firestore.collection("spaces").document(spaceId).collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(snapshots -> {
                    List<SpaceMessage> list = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        SpaceMessage m = messageFromDocument(doc);
                        if (m != null) list.add(m);
                    }
                    if (listener != null) listener.onMessagesUpdated(list);
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onMessagesUpdated(new ArrayList<>());
                });
    }

    public void sendMessage(String spaceId, String text, String type, int amount, String referenceId) {
        sendMessage(spaceId, text, type, amount, referenceId, null);
    }

    /**
     * Writes a chat message into a space.
     *
     * timestamp is a server timestamp so both participants order messages
     * identically regardless of their device clocks.
     *
     * @param onResult optional callback so the UI can surface a real failure
     *                 instead of silently dropping the message.
     */
    public void sendMessage(String spaceId, String text, String type, int amount,
                            String referenceId, FirebaseUsers.ActionResult onResult) {
        if (firestore == null || currentUserId.isEmpty()) {
            if (onResult != null) onResult.onFailure("You are not signed in");
            return;
        }
        if (spaceId == null || spaceId.isEmpty()) {
            if (onResult != null) onResult.onFailure("This conversation is not ready yet");
            return;
        }

        String msgId = firestore.collection("spaces").document(spaceId)
                .collection("messages").document().getId();

        SpaceMessage msg = new SpaceMessage(msgId, currentUserId, currentUserName, text,
                type, amount, System.currentTimeMillis(), "SENT", referenceId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", msg.getId());
        payload.put("senderUid", msg.getSenderUid());
        payload.put("senderName", msg.getSenderName());
        payload.put("text", msg.getText());
        payload.put("type", msg.getType());
        payload.put("amount", msg.getAmount());
        payload.put("status", msg.getStatus());
        payload.put("referenceId", msg.getReferenceId());
        payload.put("timestamp", FieldValue.serverTimestamp());

        firestore.collection("spaces").document(spaceId).collection("messages").document(msgId)
                .set(payload)
                .addOnSuccessListener(unused -> {
                    if (onResult != null) onResult.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (onResult != null) {
                        onResult.onFailure(e.getMessage() != null ? e.getMessage() : "Message not sent");
                    }
                });
    }

    /**
     * Proposes a payment inside a space: writes the settlement the recipient must
     * confirm, plus the chat card that carries its id.
     *
     * @param toUid the person being paid — they are the only one who can confirm.
     */
    public void createSettlement(String spaceId, String toUid, long amountPaise) {
        createSettlement(spaceId, toUid, amountPaise, null);
    }

    public void createSettlement(String spaceId, String toUid, long amountPaise,
                                 FirebaseUsers.ActionResult onResult) {
        if (firestore == null || currentUserId.isEmpty()) {
            if (onResult != null) onResult.onFailure("You are not signed in");
            return;
        }
        if (spaceId == null || spaceId.isEmpty() || toUid == null || toUid.isEmpty()) {
            if (onResult != null) onResult.onFailure("This conversation is not ready yet");
            return;
        }
        if (amountPaise <= 0) {
            if (onResult != null) onResult.onFailure("Enter an amount greater than zero");
            return;
        }

        String stId = firestore.collection("spaces").document(spaceId)
                .collection("settlements").document().getId();

        SpaceSettlement st = new SpaceSettlement(stId, currentUserId, toUid, amountPaise,
                System.currentTimeMillis(), "PENDING");

        firestore.collection("spaces").document(spaceId).collection("settlements").document(stId)
                .set(st)
                .addOnSuccessListener(unused -> {
                    sendMessage(spaceId,
                            "Proposed payment of " + Money.formatInr(amountPaise),
                            "SETTLEMENT", (int) (amountPaise / 100), stId);
                    if (onResult != null) onResult.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (onResult != null) {
                        onResult.onFailure(e.getMessage() != null ? e.getMessage() : "Payment not recorded");
                    }
                });
    }

    /**
     * Confirms or disputes a settlement.
     *
     * The security rules only let the payee change the status, which is exactly
     * the point: a payment is real only when the person receiving it agrees.
     */
    public void updateSettlementStatus(String spaceId, String settlementId, String status) {
        updateSettlementStatus(spaceId, settlementId, status, null);
    }

    public void updateSettlementStatus(String spaceId, String settlementId, String status,
                                       FirebaseUsers.ActionResult onResult) {
        if (firestore == null || currentUserId.isEmpty()) {
            if (onResult != null) onResult.onFailure("You are not signed in");
            return;
        }
        if (spaceId == null || spaceId.isEmpty() || settlementId == null || settlementId.isEmpty()) {
            if (onResult != null) onResult.onFailure("This payment could not be found");
            return;
        }

        firestore.collection("spaces").document(spaceId)
                .collection("settlements").document(settlementId)
                .update("status", status)
                .addOnSuccessListener(unused -> {
                    if (onResult != null) onResult.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (onResult != null) {
                        onResult.onFailure(e.getMessage() != null ? e.getMessage() : "Could not update payment");
                    }
                });
    }

    public void raiseDispute(String spaceId, String expenseId, String title, String reason) {
        if (firestore == null || currentUserId.isEmpty()) return;
        String dId = firestore.collection("disputes").document().getId();
        Dispute d = new Dispute(dId, spaceId, expenseId, title, currentUserId, currentUserName, reason, "ACTIVE");
        firestore.collection("disputes").document(dId).set(d)
                .addOnFailureListener(e ->
                        android.util.Log.e("TabRepository", "Could not raise dispute", e));
        sendMessage(spaceId, "Query raised on: " + title + " (" + reason + ")", "QUERY", 0, expenseId);
    }

    public void resolveDisputeAction(String spaceId, String expenseId, String disputeId, String action, long adjustedAmountPaise) {
        if (firestore == null || currentUserId.isEmpty()) return;

        firestore.collection("spaces").document(spaceId).collection("expenses").document(expenseId).get()
                .addOnSuccessListener(expDoc -> {
                    SpaceExpense exp = expDoc.toObject(SpaceExpense.class);
                    if (exp == null) return;

                    if ("REMOVE".equals(action)) {
                        Map<String, Long> shares = exp.getShares();
                        shares.remove(currentUserId);
                        if (!shares.isEmpty()) {
                            Map<String, Long> recomputed = SplitCalculator.splitEqually(exp.getAmountPaise(), new ArrayList<>(shares.keySet()));
                            firestore.collection("spaces").document(spaceId).collection("expenses").document(expenseId)
                                    .update("shares", recomputed);
                        }
                    } else if ("ADJUST".equals(action)) {
                        Map<String, Long> shares = SplitCalculator.splitEqually(adjustedAmountPaise, new ArrayList<>(exp.getShares().keySet()));
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("amountPaise", adjustedAmountPaise);
                        updates.put("shares", shares);
                        firestore.collection("spaces").document(spaceId).collection("expenses").document(expenseId)
                                .update(updates);
                    }

                    if (disputeId != null && !disputeId.isEmpty()) {
                        firestore.collection("disputes").document(disputeId).delete();
                    }
                });
    }

    public interface OnBackupCompleteListener {
        void onComplete(boolean success, String message);
    }

    public void saveCloudBackup(OnBackupCompleteListener listener) {
        if (firestore == null || currentUserId.isEmpty()) {
            if (listener != null) listener.onComplete(false, "Not logged in");
            return;
        }

        Map<String, Object> backupData = new HashMap<>();
        backupData.put("uid", currentUserId);
        backupData.put("userName", currentUserName);
        backupData.put("backedUpAt", System.currentTimeMillis());

        firestore.collection("users").document(currentUserId).collection("friends").get()
                .addOnSuccessListener(friendSnaps -> {
                    List<Map<String, Object>> friendsList = new ArrayList<>();
                    for (DocumentSnapshot doc : friendSnaps.getDocuments()) {
                        friendsList.add(doc.getData());
                    }
                    backupData.put("friends", friendsList);

                    firestore.collection("spaces").whereArrayContains("memberUids", currentUserId).get()
                            .addOnSuccessListener(spaceSnaps -> {
                                List<Map<String, Object>> spacesList = new ArrayList<>();
                                for (DocumentSnapshot doc : spaceSnaps.getDocuments()) {
                                    spacesList.add(doc.getData());
                                }
                                backupData.put("spaces", spacesList);

                                firestore.collection("users").document(currentUserId).collection("backups").document("latest")
                                        .set(backupData)
                                        .addOnSuccessListener(aVoid -> {
                                            if (listener != null) listener.onComplete(true, "Cloud backup saved successfully! ☁️");
                                        })
                                        .addOnFailureListener(e -> {
                                            if (listener != null) listener.onComplete(false, "Backup failed: " + e.getMessage());
                                        });
                            })
                            .addOnFailureListener(e -> {
                                if (listener != null) listener.onComplete(false, "Backup failed: " + e.getMessage());
                            });
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onComplete(false, "Backup failed: " + e.getMessage());
                });
    }

    public void addTransitRoute(String from, String to, int fare) {
        if (firestore == null || currentUserId.isEmpty()) return;

        Map<String, Object> route = new HashMap<>();
        route.put("from", from);
        route.put("to", to);
        route.put("fare", fare);
        route.put("createdBy", currentUserId);
        route.put("createdAt", System.currentTimeMillis());

        firestore.collection("transit_routes").add(route)
                .addOnFailureListener(e ->
                        android.util.Log.e("TabRepository", "Could not save transit route", e));
    }

    /** Creates a shared expense in a space and cards it into the chat. */
    public void addExpense(String spaceId, String title, long amountPaise,
                           String paidByUid, List<String> participantUids,
                           FirebaseUsers.ActionResult onResult) {
        if (firestore == null || currentUserId.isEmpty()) {
            if (onResult != null) onResult.onFailure("You are not signed in");
            return;
        }
        if (spaceId == null || spaceId.isEmpty()) {
            if (onResult != null) onResult.onFailure("This conversation is not ready yet");
            return;
        }
        if (amountPaise <= 0) {
            if (onResult != null) onResult.onFailure("Enter an amount greater than zero");
            return;
        }

        List<String> participants = new ArrayList<>();
        if (participantUids != null) {
            for (String u : participantUids) {
                if (u != null && !u.isEmpty() && !participants.contains(u)) participants.add(u);
            }
        }
        if (participants.isEmpty()) participants.add(currentUserId);

        String payer = paidByUid != null && !paidByUid.isEmpty() ? paidByUid : currentUserId;
        Map<String, Long> shares = SplitCalculator.splitEqually(amountPaise, participants);

        String expenseId = firestore.collection("spaces").document(spaceId)
                .collection("expenses").document().getId();

        SpaceExpense expense = new SpaceExpense();
        expense.setId(expenseId);
        expense.setTitle(title != null && !title.isEmpty() ? title : "Shared expense");
        expense.setAmountPaise(amountPaise);
        expense.setPaidByUid(payer);
        expense.setShares(shares);
        expense.setOccurredAt(System.currentTimeMillis());
        expense.setCreatedBy(currentUserId);
        expense.setStatus("ACTIVE");

        firestore.collection("spaces").document(spaceId).collection("expenses").document(expenseId)
                .set(expense)
                .addOnSuccessListener(unused -> {
                    // The chat card is what lets the other members see and query it.
                    sendMessage(spaceId,
                            expense.getTitle() + " · paid by " +
                                    (payer.equals(currentUserId) ? "you" : nameFor(payer)),
                            "EXPENSE", (int) (amountPaise / 100), expenseId);
                    if (onResult != null) onResult.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (onResult != null) {
                        onResult.onFailure(e.getMessage() != null ? e.getMessage() : "Expense not saved");
                    }
                });
    }

    /** Best known display name for a uid, falling back to the id. */
    public String nameFor(String uid) {
        if (uid == null) return "someone";
        if (uid.equals(currentUserId)) return currentUserName;
        String cached = friendNames.get(uid);
        if (cached != null) return cached;
        cached = userCache.get(uid);
        if (cached != null) return cached;
        Person p = getPersonById(uid);
        if (p != null) return p.getName();
        return "TABS user";
    }

    public List<TransitRoute> getTransitRoutes() { return new ArrayList<>(transitRoutes); }
}
