package com.example.tab_able;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tab_able.data.FirebaseUsers;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.TabRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;

/**
 * The real friends screen: search a person, send them a request, and accept or
 * decline whatever comes back.
 *
 * Everything here reads and writes Firestore, so the state is shared between
 * accounts rather than being local UI decoration.
 */
public class AddFriendsActivity extends AppCompatActivity implements TabRepository.DataChangeListener {

    private EditText etInviteEmail;
    private TextView btnInviteEmail, btnLetsGo;
    private TextView tvSearchStatus;

    private LinearLayout layoutSearchResult;
    private LinearLayout layoutIncomingSection, layoutIncomingContainer;
    private LinearLayout layoutOutgoingSection, layoutOutgoingContainer;
    private LinearLayout layoutFriendsSection, layoutFriendsContainer;
    private LinearLayout layoutFriendsEmpty;
    private TextView tvFriendsHeader, tvEmptyTitle, tvEmptyBody;

    private TabRepository repo;
    private FirebaseUsers usersApi;

    private ListenerRegistration requestsReg;
    private boolean searchInFlight = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_friends);

        repo = TabRepository.getInstance();
        usersApi = repo.getUsersApi();

        etInviteEmail = findViewById(R.id.et_invite_email);
        btnInviteEmail = findViewById(R.id.btn_invite_email);
        btnLetsGo = findViewById(R.id.btn_lets_go);
        tvSearchStatus = findViewById(R.id.tv_search_status);
        layoutSearchResult = findViewById(R.id.layout_search_result);

        layoutIncomingSection = findViewById(R.id.layout_incoming_section);
        layoutIncomingContainer = findViewById(R.id.layout_incoming_container);
        layoutOutgoingSection = findViewById(R.id.layout_outgoing_section);
        layoutOutgoingContainer = findViewById(R.id.layout_outgoing_container);
        layoutFriendsSection = findViewById(R.id.layout_friends_section);
        layoutFriendsContainer = findViewById(R.id.layout_friends_container);
        layoutFriendsEmpty = findViewById(R.id.layout_friends_empty);
        tvFriendsHeader = findViewById(R.id.tv_friends_header);
        tvEmptyTitle = findViewById(R.id.tv_empty_title);
        tvEmptyBody = findViewById(R.id.tv_empty_body);

        if (usersApi == null) {
            Toast.makeText(this, "Firebase is not configured on this build", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        btnInviteEmail.setOnClickListener(v -> runSearch());
        btnLetsGo.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        repo.addDataChangeListener(this);
        startRequestListener();
        render();
    }

    // ------------------------------------------------------------------ search

    private void runSearch() {
        String query = etInviteEmail.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Type an email or username to search", Toast.LENGTH_SHORT).show();
            return;
        }
        if (searchInFlight) return;

        final String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            Toast.makeText(this, "Please log in again", Toast.LENGTH_SHORT).show();
            return;
        }

        searchInFlight = true;
        layoutSearchResult.removeAllViews();
        layoutSearchResult.setVisibility(View.GONE);
        showStatus("searching for " + query + "…");

        usersApi.findUserByQuery(query, new FirebaseUsers.UserLookup() {
            @Override
            public void onFound(String uid) {
                searchInFlight = false;
                if (uid.equals(myUid)) {
                    showStatus("That is you! Share your username with a friend instead.");
                    return;
                }
                if (isAlreadyFriend(uid)) {
                    showStatus("You are already friends with this person 🎉");
                    return;
                }
                // Offer the send rather than pre-judging it: if the recipient has no
                // profile document yet the write itself will fail, and sendRequest
                // turns that into a specific, actionable message.
                showFoundPerson(uid, query);
            }

            @Override
            public void onNotFound() {
                searchInFlight = false;
                showStatus("No TABS account for \"" + query + "\".");
                if (query.contains("@")) {
                    offerEmailInvite(query);
                }
            }

            @Override
            public void onError(String message) {
                searchInFlight = false;
                showStatus("Search failed: " + message);
            }
        });
    }

    /**
     * Offers to send a request to a resolved uid.
     *
     * The typed query is used as the label because profiles are private: we can
     * resolve the username or email to a uid, but we cannot read that person's
     * name until they accept. Their real name arrives with the acceptance.
     */
    private void showFoundPerson(final String uid, final String query) {
        showStatus("found an account for \"" + query + "\" 👇");
        layoutSearchResult.setVisibility(View.VISIBLE);

        View row = inflateRow(layoutSearchResult);
        bindRow(row, "🙂", query, "TABS member - name appears once they accept");

        TextView primary = row.findViewById(R.id.btn_row_primary);
        primary.setText("send request");
        primary.setOnClickListener(v -> sendRequest(uid, query, row));
    }

    private void sendRequest(final String targetUid, final String targetName, View row) {
        final String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;

        TextView primary = row.findViewById(R.id.btn_row_primary);
        primary.setText("sending…");
        primary.setEnabled(false);

        usersApi.sendFriendRequest(myUid, repo.getCurrentUserName(), targetUid,
                new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() {
                        primary.setText("request sent ✓");
                        primary.setBackgroundResource(R.drawable.bg_pill_cream);
                        showStatus("Request sent to " + targetName + ". They have to accept it.");
                        Toast.makeText(AddFriendsActivity.this,
                                "Friend request sent to " + targetName + " 📨", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(String message) {
                        primary.setText("retry");
                        primary.setEnabled(true);

                        // Distinguish "their account exists but is not finished" from a
                        // genuine failure. The friend request is written next to the
                        // recipient's profile, so a recipient with no profile document
                        // cannot receive one until they sign in once.
                        usersApi.profileExists(targetUid, exists -> runOnUiThread(() -> {
                            if (exists) {
                                showStatus("Could not send the request: " + message);
                            } else {
                                showStatus("\"" + targetName + "\" has an account but no profile yet."
                                        + " Ask them to open TABS and log in once, then send again.");
                            }
                        }));
                    }
                });
    }

    // ---------------------------------------------------------------- requests

    private void startRequestListener() {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;

        requestsReg = usersApi.listenToRequests(myUid, (incoming, outgoing) -> runOnUiThread(() -> {
            renderIncoming(incoming);
            renderOutgoing(outgoing);
            render();
        }));
    }

    private void renderIncoming(List<FirebaseUsers.FriendRequest> requests) {
        layoutIncomingContainer.removeAllViews();

        List<FirebaseUsers.FriendRequest> pending = new ArrayList<>();
        for (FirebaseUsers.FriendRequest r : requests) {
            if (FirebaseUsers.STATUS_PENDING.equals(r.status)) pending.add(r);
        }

        if (pending.isEmpty()) {
            layoutIncomingSection.setVisibility(View.GONE);
            return;
        }

        layoutIncomingSection.setVisibility(View.VISIBLE);
        for (FirebaseUsers.FriendRequest r : pending) {
            View row = inflateRow(layoutIncomingContainer);
            bindRow(row, "👋", r.otherName, "wants to split tabs with you");

            TextView accept = row.findViewById(R.id.btn_row_primary);
            accept.setText("accept");
            accept.setOnClickListener(v -> respondToRequest(r, true, row));

            TextView decline = row.findViewById(R.id.btn_row_secondary);
            decline.setVisibility(View.VISIBLE);
            decline.setText("decline");
            decline.setOnClickListener(v -> respondToRequest(r, false, row));
        }
    }

    private void respondToRequest(final FirebaseUsers.FriendRequest request,
                                  final boolean accept,
                                  final View row) {
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;

        TextView primary = row.findViewById(R.id.btn_row_primary);
        primary.setEnabled(false);
        primary.setText(accept ? "accepting…" : "declining…");

        FirebaseUsers.ActionResult callback = new FirebaseUsers.ActionResult() {
            @Override
            public void onSuccess() {
                Toast.makeText(AddFriendsActivity.this,
                        accept ? request.otherName + " is now your friend 🎉"
                               : "Request declined",
                        Toast.LENGTH_SHORT).show();
                render();
            }

            @Override
            public void onFailure(String message) {
                primary.setEnabled(true);
                primary.setText(accept ? "accept" : "decline");
                Toast.makeText(AddFriendsActivity.this,
                        "Could not respond: " + message, Toast.LENGTH_LONG).show();
            }
        };

        if (accept) {
            usersApi.acceptFriendRequest(myUid, repo.getCurrentUserName(), request.otherUid, callback);
        } else {
            usersApi.declineFriendRequest(myUid, request.otherUid, callback);
        }
    }

    private void renderOutgoing(List<FirebaseUsers.FriendRequest> requests) {
        layoutOutgoingContainer.removeAllViews();

        List<FirebaseUsers.FriendRequest> pending = new ArrayList<>();
        for (FirebaseUsers.FriendRequest r : requests) {
            if (FirebaseUsers.STATUS_PENDING.equals(r.status)) pending.add(r);
        }

        if (pending.isEmpty()) {
            layoutOutgoingSection.setVisibility(View.GONE);
            return;
        }

        layoutOutgoingSection.setVisibility(View.VISIBLE);
        for (FirebaseUsers.FriendRequest r : pending) {
            View row = inflateRow(layoutOutgoingContainer);
            bindRow(row, "📤", r.otherName, "waiting for them to accept");

            TextView cancel = row.findViewById(R.id.btn_row_primary);
            cancel.setText("cancel");
            cancel.setBackgroundResource(R.drawable.bg_pill_outlined);
            cancel.setOnClickListener(v -> {
                String myUid = FirebaseAuth.getInstance().getUid();
                if (myUid == null) return;
                cancel.setEnabled(false);
                cancel.setText("cancelling…");
                usersApi.cancelFriendRequest(myUid, r.otherUid, new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(AddFriendsActivity.this, "Request withdrawn", Toast.LENGTH_SHORT).show();
                        render();
                    }

                    @Override
                    public void onFailure(String message) {
                        cancel.setEnabled(true);
                        cancel.setText("cancel");
                        Toast.makeText(AddFriendsActivity.this,
                                "Could not withdraw: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }
    }

    // ----------------------------------------------------------------- friends

    @Override
    public void onDataChanged() {
        render();
    }

    /** Redraws the friends list, the requests and the empty state. */
    private void render() {
        List<Person> friends = repo.getAllPeople();

        layoutFriendsContainer.removeAllViews();
        if (friends.isEmpty()) {
            layoutFriendsSection.setVisibility(View.GONE);
        } else {
            layoutFriendsSection.setVisibility(View.VISIBLE);
            tvFriendsHeader.setText("your friends · " + friends.size());

            for (Person p : friends) {
                View row = inflateRow(layoutFriendsContainer);
                String sub = !p.getEmail().isEmpty() ? p.getEmail() : p.getTag();
                bindRow(row, p.getEmoji(), p.getName(), sub);

                TextView chat = row.findViewById(R.id.btn_row_primary);
                chat.setText("chat");
                chat.setOnClickListener(v -> openChat(p));
            }
        }

        renderEmptyState();
    }

    private void renderEmptyState() {
        boolean nothingYet = repo.getAllPeople().isEmpty()
                && layoutIncomingSection.getVisibility() != View.VISIBLE
                && layoutOutgoingSection.getVisibility() != View.VISIBLE;

        if (!nothingYet) {
            layoutFriendsEmpty.setVisibility(View.GONE);
            return;
        }

        layoutFriendsEmpty.setVisibility(View.VISIBLE);
        tvEmptyTitle.setText("no friends yet");
        tvEmptyBody.setText("Search someone by their exact username or email above, "
                + "then send them a request. They have to accept it.");
    }

    // ------------------------------------------------------------------ helpers

    private View inflateRow(LinearLayout parent) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_person_row, parent, false);
        parent.addView(row);
        return row;
    }

    private void bindRow(View row, String emoji, String name, String subtitle) {
        TextView tvEmoji = row.findViewById(R.id.tv_row_emoji);
        TextView tvName = row.findViewById(R.id.tv_row_name);
        TextView tvSub = row.findViewById(R.id.tv_row_sub);

        tvEmoji.setText(emoji);
        tvName.setText(name);
        if (TextUtils.isEmpty(subtitle)) {
            tvSub.setVisibility(View.GONE);
        } else {
            tvSub.setVisibility(View.VISIBLE);
            tvSub.setText(subtitle);
        }
    }

    private boolean isAlreadyFriend(String uid) {
        return repo.getPersonById(uid) != null;
    }

    private void openChat(Person person) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("person_id", person.getId());
        intent.putExtra("person_name", person.getName());
        intent.putExtra("is_group", false);
        startActivity(intent);
    }

    private void showStatus(String message) {
        tvSearchStatus.setVisibility(View.VISIBLE);
        tvSearchStatus.setText(message);
    }

    /** When nobody owns that email, offer a plain email invite instead. */
    private void offerEmailInvite(final String email) {
        layoutSearchResult.setVisibility(View.VISIBLE);

        View row = inflateRow(layoutSearchResult);
        bindRow(row, "✉️", email, "not on TABS yet");

        TextView invite = row.findViewById(R.id.btn_row_primary);
        invite.setText("email invite");
        invite.setOnClickListener(v -> {
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
            emailIntent.setData(Uri.parse("mailto:" + email));
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Join me on TABS! 💸");
            emailIntent.putExtra(Intent.EXTRA_TEXT,
                    "Hey! Join me on TABS to split expenses and settle up: https://tabs.app");
            try {
                startActivity(emailIntent);
            } catch (Exception e) {
                Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        repo.removeDataChangeListener(this);
        if (requestsReg != null) {
            requestsReg.remove();
            requestsReg = null;
        }
    }
}
