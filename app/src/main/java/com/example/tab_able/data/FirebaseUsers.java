package com.example.tab_able.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that touches users, profiles, lookups and friend requests.
 *
 * Firestore shape used here:
 *
 *   users/{uid}                            { uid, email, displayName, username, ... }
 *   emailLookup/{emailLower}               { uid }        uniqueness for email
 *   usernames/{usernameLower}              { uid }        uniqueness for username
 *   users/{uid}/friends/{friendUid}        { friendUid, since } mutual friendship edge
 *   friendRequests/{sortedUids}            { fromUid, toUid, fromName, toName, status }
 *
 * Friend requests are real: they start PENDING and only the recipient can move
 * them to ACCEPTED or DECLINED. A friendship only becomes mutual once the
 * recipient accepts AND both sides write their own friends edge.
 *
 * The request document lives at a deterministic id derived from the two uids,
 * which means:
 *   - there can only ever be one request between two people (no duplicates),
 *   - both parties can read the very same document, so the sender sees the
 *     acceptance the moment it happens.
 */
public final class FirebaseUsers {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DECLINED = "DECLINED";

    /** Result of a user lookup: exactly one of found/notFound/error is used. */
    public interface UserLookup {
        void onFound(@NonNull String uid);
        void onNotFound();
        void onError(@NonNull String message);
    }

    /** Outcome of a friend-request action. */
    public interface ActionResult {
        void onSuccess();
        void onFailure(@NonNull String message);
    }

    /** A pending request, in either direction. */
    public static final class FriendRequest {
        public final String otherUid;
        public final String otherName;
        public final String status;
        public final long createdAt;
        public final boolean incoming;

        public FriendRequest(String otherUid, String otherName, String status,
                             long createdAt, boolean incoming) {
            this.otherUid = otherUid;
            this.otherName = otherName != null && !otherName.isEmpty() ? otherName : "Someone";
            this.status = status != null ? status : STATUS_PENDING;
            this.createdAt = createdAt;
            this.incoming = incoming;
        }
    }

    public interface RequestsListener {
        void onRequestsChanged(@NonNull List<FriendRequest> incoming,
                               @NonNull List<FriendRequest> outgoing);
    }

    private final FirebaseFirestore db;

    public FirebaseUsers(@NonNull FirebaseFirestore db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ profile

    /** Writes the full profile for a brand-new account, claiming both lookups. */
    public void createProfile(@NonNull String uid,
                              @NonNull String email,
                              @NonNull String displayName,
                              @Nullable String username,
                              @Nullable ActionResult callback) {
        String emailKey = normalise(email);
        String usernameKey = username != null ? normalise(username) : "";

        Map<String, Object> profile = new HashMap<>();
        profile.put("uid", uid);
        profile.put("email", email);
        profile.put("emailLower", emailKey);
        profile.put("displayName", displayName);
        profile.put("username", usernameKey.isEmpty() ? null : usernameKey);
        profile.put("createdAt", FieldValue.serverTimestamp());
        profile.put("updatedAt", FieldValue.serverTimestamp());
        WriteBatch batch = db.batch();
        batch.set(db.collection("users").document(uid), profile);

        Map<String, Object> emailLookup = new HashMap<>();
        emailLookup.put("uid", uid);
        emailLookup.put("email", emailKey);
        batch.set(db.collection("emailLookup").document(emailKey), emailLookup);

        if (!usernameKey.isEmpty()) {
            Map<String, Object> unameLookup = new HashMap<>();
            unameLookup.put("uid", uid);
            unameLookup.put("username", usernameKey);
            batch.set(db.collection("usernames").document(usernameKey), unameLookup);
        }

        batch.commit()
                .addOnSuccessListener(unused -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) {
                        callback.onFailure(e.getMessage() != null ? e.getMessage() : "Could not save profile");
                    }
                });
    }

    /**
     * Fills in any profile fields that are missing (for accounts created before the
     * full profile existed, or created straight from the Firebase console) and
     * guarantees the lookup documents exist.
     *
     * All three documents are written in ONE batch, exactly like createProfile.
     * They used to be written as two sequential steps - the profile first, then its
     * lookups - and if the app died, lost its token, or the process was killed
     * between them, the account was left with lookup entries pointing at a uid that
     * had no profile. That state is genuinely broken: the person is findable by
     * search, but friend requests to them fail, because the rules require the
     * recipient's profile document to exist.
     */
    public void healProfile(@NonNull String uid,
                            @NonNull String email,
                            @NonNull String displayName,
                            @Nullable String username) {
        String emailKey = normalise(email);
        String usernameKey = username != null ? normalise(username) : "";

        Map<String, Object> patch = new HashMap<>();
        patch.put("uid", uid);
        if (!email.isEmpty()) patch.put("email", email);
        if (!emailKey.isEmpty()) patch.put("emailLower", emailKey);
        if (!displayName.isEmpty()) patch.put("displayName", displayName);
        if (!usernameKey.isEmpty()) patch.put("username", usernameKey);
        patch.put("updatedAt", FieldValue.serverTimestamp());

        WriteBatch batch = db.batch();
        batch.set(db.collection("users").document(uid), patch, SetOptions.merge());

        if (!emailKey.isEmpty()) {
            batch.set(db.collection("emailLookup").document(emailKey),
                    lookupDoc(uid, "email", emailKey), SetOptions.merge());
        }
        if (!usernameKey.isEmpty()) {
            batch.set(db.collection("usernames").document(usernameKey),
                    lookupDoc(uid, "username", usernameKey), SetOptions.merge());
        }

        batch.commit().addOnFailureListener(e ->
                android.util.Log.e("FirebaseUsers", "Could not repair profile for " + uid, e));
    }

    /**
     * Deletes lookup entries that point at a uid which no longer has a profile, so
     * the key can be claimed again.
     *
     * This is what makes "every existing user is searchable" true in both
     * directions: an orphaned entry would otherwise permanently block a real
     * person from registering that email or username, and searching it would find
     * a dead end.
     *
     * @param keys lookup document ids to inspect
     * @param collection "emailLookup" or "usernames"
     */
    public void dropOrphanedLookups(@NonNull List<String> keys,
                                    @NonNull String collection,
                                    @Nullable Runnable done) {
        if (keys.isEmpty()) {
            if (done != null) done.run();
            return;
        }

        final int[] pending = {keys.size()};
        Runnable step = () -> {
            if (--pending[0] <= 0 && done != null) done.run();
        };

        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                step.run();
                continue;
            }
            db.collection(collection).document(key)
                    .get()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null
                                && task.getResult().exists()) {
                            String owner = task.getResult().getString("uid");
                            if (owner != null && !owner.isEmpty()) {
                                profileExists(owner, exists -> {
                                    if (!exists) {
                                        db.collection(collection).document(key).delete();
                                        android.util.Log.i("FirebaseUsers",
                                                "Removed orphaned " + collection + "/" + key);
                                    }
                                    step.run();
                                });
                                return;
                            }
                        }
                        step.run();
                    });
        }
    }

    private static Map<String, Object> lookupDoc(String uid, String keyName, String keyValue) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("uid", uid);
        doc.put(keyName, keyValue);
        return doc;
    }

    /**
     * Reads users/{uid} and pushes it into {@link CurrentUser}.
     * Falls back to whatever FirebaseAuth already knows when the document is
     * missing, so a slow first fetch never leaves the user nameless.
     */
    public void loadProfile(@NonNull String uid, @Nullable Runnable done) {
        db.collection("users").document(uid)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                        DocumentSnapshot doc = task.getResult();
                        String name = firstNonEmpty(
                                doc.getString("displayName"),
                                doc.getString("username"),
                                CurrentUser.getDisplayName());
                        String username = firstNonEmpty(doc.getString("username"), CurrentUser.getUsername());
                        String email = firstNonEmpty(doc.getString("email"), CurrentUser.getEmail());
                        CurrentUser.setSession(uid, username, name, email);
                    }
                    if (done != null) done.run();
                });
    }

    // ------------------------------------------------------------------ lookups

    /** Resolves a typed email-or-username to a uid. */
    public void findUserByQuery(@NonNull String rawQuery, @NonNull UserLookup callback) {
        String query = rawQuery.trim();
        if (query.isEmpty()) {
            callback.onNotFound();
            return;
        }
        String key = normalise(query);

        if (key.contains("@")) {
            findUserByEmail(key, callback);
        } else {
            findUserByUsername(key, callback);
        }
    }

    /**
     * Does this uid actually have a profile document?
     *
     * A search can resolve an email or username to a uid whose users/{uid} document
     * does not exist — that happens when an account was created but never finished
     * onboarding, or was made by hand in the Firebase console and never signed in.
     * Such a person cannot receive a friend request, so the UI needs to be able to
     * say so instead of reporting a generic failure.
     */
    public void profileExists(@NonNull String uid, @NonNull ProfileCheck callback) {
        if (uid.isEmpty()) {
            callback.onResult(false);
            return;
        }
        // Deliberately distinct from a permission error: Firebase reports 403 for
        // "no such document" on a rules-protected read, which is why the app check
        // below is the real signal.
        db.collection("users").document(uid)
                .get()
                .addOnCompleteListener(task -> {
                    boolean exists = task.isSuccessful()
                            && task.getResult() != null
                            && task.getResult().exists();
                    callback.onResult(exists);
                });
    }

    public interface ProfileCheck {
        void onResult(boolean exists);
    }

    /**
     * Resolves an email to a uid through the emailLookup collection.
     *
     * Deliberately does NOT fall back to scanning the users collection: profiles
     * are private and listing them is denied by the rules, so such a query would
     * always fail. The lookup document is written at registration and kept in sync
     * by healProfile, so it is the reliable path.
     */
    public void findUserByEmail(@NonNull String rawEmail, @NonNull UserLookup callback) {
        String email = normalise(rawEmail);
        if (email.isEmpty()) {
            callback.onNotFound();
            return;
        }

        db.collection("emailLookup").document(email)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                        String uid = task.getResult().getString("uid");
                        if (isUsable(uid)) {
                            callback.onFound(uid);
                            return;
                        }
                    }
                    callback.onNotFound();
                })
                .addOnFailureListener(e -> callback.onError(failure(e)));
    }

    /** Resolves a username to a uid through the usernames collection. */
    public void findUserByUsername(@NonNull String rawUsername, @NonNull UserLookup callback) {
        String username = normalise(rawUsername);
        if (username.isEmpty()) {
            callback.onNotFound();
            return;
        }

        db.collection("usernames").document(username)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                        String uid = task.getResult().getString("uid");
                        if (isUsable(uid)) {
                            callback.onFound(uid);
                            return;
                        }
                    }
                    callback.onNotFound();
                })
                .addOnFailureListener(e -> callback.onError(failure(e)));
    }

    /**
     * Is this username already claimed by someone else?
     *
     * Must be called while signed in: the usernames collection intentionally
     * denies reads to unauthenticated clients, so a check made before signup
     * would always fail. That is why RegisterActivity creates the account first
     * and then claims the username.
     */
    public void isUsernameTaken(@NonNull String rawUsername, @NonNull UsernameCheck callback) {
        String username = normalise(rawUsername);
        if (username.isEmpty()) {
            callback.onResult(false, null);
            return;
        }

        db.collection("usernames").document(username)
                .get()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null) {
                        callback.onResult(false, null);
                        return;
                    }
                    DocumentSnapshot doc = task.getResult();
                    if (!doc.exists()) {
                        callback.onResult(false, null);
                        return;
                    }
                    String owner = doc.getString("uid");
                    String mine = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
                    boolean takenByOther = owner != null && mine != null && !owner.equals(mine);
                    callback.onResult(takenByOther, owner);
                });
    }

    public interface UsernameCheck {
        /**
         * @param taken      true when somebody else already owns it
         * @param ownerUid   the uid that owns it, when known
         */
        void onResult(boolean taken, @Nullable String ownerUid);
    }

    // ---------------------------------------------------------- friend requests

    /**
     * Id for the single request document between two people:
     * {@code fromUid_toUid}, unsorted.
     *
     * The first argument must be the SENDER and the second the recipient, so that
     * the id always matches the document's own fromUid/toUid fields — the security
     * rules derive the expected id exactly that way.
     *
     * Because either side may have created the request, reads and deletes consider
     * both orderings via {@link #bothRequestIds}.
     */
    @NonNull
    public static String requestId(@NonNull String fromUid, @NonNull String toUid) {
        return fromUid + "_" + toUid;
    }

    /**
     * Both possible request ids for a pair, since either side may have written it.
     * Index 0 is "as if this user sent it", index 1 is "as if the other did".
     */
    @NonNull
    public static String[] bothRequestIds(@NonNull String uid1, @NonNull String uid2) {
        return new String[] { requestId(uid1, uid2), requestId(uid2, uid1) };
    }

    /**
     * Sends a friend request as one document that BOTH people can read and that
     * only the recipient may answer. Status starts PENDING.
     */
    public void sendFriendRequest(@NonNull String myUid,
                                  @NonNull String myName,
                                  @NonNull String targetUid,
                                  @Nullable ActionResult callback) {
        if (myUid.equals(targetUid)) {
            if (callback != null) callback.onFailure("You cannot add yourself");
            return;
        }

        Map<String, Object> request = new HashMap<>();
        request.put("id", requestId(myUid, targetUid));
        request.put("fromUid", myUid);
        request.put("toUid", targetUid);
        request.put("fromName", myName);
        request.put("status", STATUS_PENDING);
        request.put("createdAt", System.currentTimeMillis());

        // Resolve the other person's name so both inboxes can render a human label.
        db.collection("users").document(targetUid).get()
                .addOnCompleteListener(nameTask -> {
                    if (nameTask.isSuccessful() && nameTask.getResult() != null
                            && nameTask.getResult().exists()) {
                        DocumentSnapshot doc = nameTask.getResult();
                        String toName = firstNonEmpty(doc.getString("displayName"), doc.getString("username"));
                        if (!toName.isEmpty()) request.put("toName", toName);
                    }
                    commitRequest(request, callback);
                });
    }

    private void commitRequest(Map<String, Object> request, @Nullable ActionResult callback) {
        String id = (String) request.get("id");
        db.collection("friendRequests").document(id)
                .set(request)
                .addOnSuccessListener(unused -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(failure(e));
                });
    }

    /**
     * Recipient accepts. The request flips to ACCEPTED first, because the rules
     * only allow a friends edge to exist once an ACCEPTED request backs it. The
     * sender's own edge is written by the sender when they observe the change.
     *
     * @param myName the accepting user's display name. It is recorded on the
     *               request so the sender can show a real name immediately —
     *               profiles are private, so the sender cannot read it directly.
     */
    public void acceptFriendRequest(@NonNull String myUid,
                                    @NonNull String myName,
                                    @NonNull String otherUid,
                                    @Nullable ActionResult callback) {
        long now = System.currentTimeMillis();

        Map<String, Object> edge = new HashMap<>();
        edge.put("friendUid", otherUid);
        if (myName != null && !myName.isEmpty()) edge.put("friendName", myName);
        edge.put("since", now);

        Map<String, Object> accepted = new HashMap<>();
        accepted.put("status", STATUS_ACCEPTED);
        accepted.put("respondedAt", now);
        if (myName != null && !myName.isEmpty()) accepted.put("toName", myName);

        // The request may live at either ordering, because whoever sent it chose.
        // Find the one that actually exists, then mark it accepted and add my
        // friendship edge — in that order, since the rules only allow the edge once
        // an ACCEPTED request backs it.
        final String[] candidates = bothRequestIds(myUid, otherUid);

        db.collection("friendRequests").document(candidates[0]).get()
                .addOnCompleteListener(probe -> {
                    String id = candidates[0];
                    if (!probe.isSuccessful() || probe.getResult() == null
                            || !probe.getResult().exists()) {
                        id = candidates[1];
                    }

                    db.collection("friendRequests").document(id)
                            .update(accepted)
                            .addOnSuccessListener(unused -> db.collection("users")
                                    .document(myUid)
                                    .collection("friends").document(otherUid)
                                    .set(edge, SetOptions.merge())
                                    .addOnSuccessListener(edgeDone -> {
                                        if (callback != null) callback.onSuccess();
                                    })
                                    .addOnFailureListener(edgeErr -> {
                                        if (callback != null) {
                                            callback.onFailure(edgeErr.getMessage() != null
                                                    ? edgeErr.getMessage()
                                                    : "Accepted, but the friendship was not saved");
                                        }
                                    }))
                            .addOnFailureListener(e -> {
                                if (callback != null) {
                                    callback.onFailure(e.getMessage() != null
                                            ? e.getMessage() : "Could not accept the request");
                                }
                            });
                });
    }

    /**
     * Recipient declines. Nothing is added to either friends list.
     *
     * Either ordering may hold the request, so both are attempted.
     */
    public void declineFriendRequest(@NonNull String myUid,
                                     @NonNull String otherUid,
                                     @Nullable ActionResult callback) {
        Map<String, Object> declined = new HashMap<>();
        declined.put("status", STATUS_DECLINED);
        declined.put("respondedAt", System.currentTimeMillis());

        updateEitherOrdering(myUid, otherUid, declined, callback);
    }

    /**
     * Applies a field update to whichever of the two request orderings exists.
     * The first attempt is the one where {@code myUid} is the sender; if no such
     * document exists the other ordering is tried.
     */
    private void updateEitherOrdering(@NonNull String myUid,
                                      @NonNull String otherUid,
                                      Map<String, Object> fields,
                                      @Nullable ActionResult callback) {
        db.collection("friendRequests").document(requestId(myUid, otherUid))
                .update(fields)
                .addOnSuccessListener(unused -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(first -> db.collection("friendRequests")
                        .document(requestId(otherUid, myUid))
                        .update(fields)
                        .addOnSuccessListener(unused -> {
                            if (callback != null) callback.onSuccess();
                        })
                        .addOnFailureListener(second -> {
                            if (callback != null) {
                                callback.onFailure(second.getMessage() != null
                                        ? second.getMessage() : "Could not update the request");
                            }
                        }));
    }

    /** Sender withdraws a request that has not been answered yet. */
    public void cancelFriendRequest(@NonNull String myUid,
                                    @NonNull String otherUid,
                                    @Nullable ActionResult callback) {
        // Remove both orderings so no stale half-request can linger. A missing
        // document is not an error, so the final callback always reports success.
        WriteBatch batch = db.batch();
        for (String id : bothRequestIds(myUid, otherUid)) {
            batch.delete(db.collection("friendRequests").document(id));
        }

        batch.commit()
                .addOnSuccessListener(unused -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(failure(e));
                });
    }

    /**
     * Watches every request this user is part of. When the other side accepts,
     * this user's own friends edge is written so the friendship becomes mutual.
     */
    public ListenerRegistration listenToRequests(@NonNull String myUid,
                                                 @NonNull RequestsListener listener) {
        final Map<String, FriendRequest> incoming = new HashMap<>();
        final Map<String, FriendRequest> outgoing = new HashMap<>();
        final List<ListenerRegistration> regs = new ArrayList<>();

        Runnable emit = () -> {
            List<FriendRequest> in = new ArrayList<>(incoming.values());
            List<FriendRequest> out = new ArrayList<>(outgoing.values());
            Collections.sort(in, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            Collections.sort(out, (a, b) -> Long.compare(b.createdAt, a.createdAt));

            // Mutual-friendship repair: they accepted, so make sure my edge exists.
            // The name travels on the request because profiles are private.
            for (FriendRequest r : out) {
                if (STATUS_ACCEPTED.equals(r.status)) {
                    selfRegisterFriend(myUid, r.otherUid, r.otherName);
                }
            }
            listener.onRequestsChanged(in, out);
        };

        // Explicit limit: the security rules only permit listing when the query is
        // bounded, and it also keeps the read cost predictable.
        regs.add(db.collection("friendRequests")
                .whereEqualTo("toUid", myUid)
                .limit(50)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    incoming.clear();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        incoming.put(doc.getId(), new FriendRequest(
                                doc.getString("fromUid"),
                                doc.getString("fromName"),
                                doc.getString("status"),
                                doc.getLong("createdAt") != null ? doc.getLong("createdAt") : 0L,
                                true));
                    }
                    emit.run();
                }));

        regs.add(db.collection("friendRequests")
                .whereEqualTo("fromUid", myUid)
                .limit(50)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    outgoing.clear();
                    for (DocumentSnapshot doc : snapshots.getDocuments()) {
                        outgoing.put(doc.getId(), new FriendRequest(
                                doc.getString("toUid"),
                                doc.getString("toName"),
                                doc.getString("status"),
                                doc.getLong("createdAt") != null ? doc.getLong("createdAt") : 0L,
                                false));
                    }
                    emit.run();
                }));

        return new ListenerRegistration() {
            @Override
            public void remove() {
                for (ListenerRegistration r : regs) {
                    if (r != null) r.remove();
                }
                regs.clear();
            }
        };
    }

    private void selfRegisterFriend(@NonNull String myUid, @NonNull String otherUid,
                                    @Nullable String otherName) {
        Map<String, Object> edge = new HashMap<>();
        edge.put("friendUid", otherUid);
        if (otherName != null && !otherName.isEmpty()) edge.put("friendName", otherName);
        edge.put("since", System.currentTimeMillis());

        // merge() so an existing edge is not disturbed, and so a later accept
        // cannot fail just because the edge is already there.
        db.collection("users").document(myUid).collection("friends").document(otherUid)
                .set(edge, SetOptions.merge());
    }
    // ------------------------------------------------------------------ utils

    /** Lower-cased, trimmed key used for all lookup documents. */
    public static String normalise(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static boolean isUsable(@Nullable String uid) {
        return uid != null && !uid.isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static String failure(@NonNull Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "Something went wrong. Check your connection.";
        // Surface the one failure that otherwise looks like a mysterious no-op.
        if (m.contains("not exist") || m.contains("NOT_FOUND")) {
            return "Firestore database not found in this Firebase project. Create it in the "
                    + "Firebase console (Build > Firestore Database > Create database).";
        }
        if (m.contains("PERMISSION_DENIED") || m.contains("permission")) {
            return "Permission denied by Firestore rules. Deploy firestore.rules.";
        }
        return m;
    }
}
