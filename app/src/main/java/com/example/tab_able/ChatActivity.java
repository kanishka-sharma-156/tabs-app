package com.example.tab_able;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.example.tab_able.data.FirebaseUsers;
import com.example.tab_able.data.Group;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.Space;
import com.example.tab_able.data.SpaceMessage;
import com.example.tab_able.data.TabRepository;
import com.example.tab_able.logic.Money;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One conversation: a 1-to-1 direct tab or a group pool.
 *
 * Real behaviour:
 *  - 1-to-1 chats resolve the peer's uid and make sure the DIRECT space document
 *    exists before any message is written, because messages, expenses and
 *    settlements all live under that space and the security rules require the
 *    writer to be listed as a member.
 *  - payments write a settlement the OTHER person must confirm, which is what
 *    makes the balance actually move.
 *  - settlement cards track the real settlement status, and only the payee gets
 *    the confirm / dispute buttons.
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    /** group id when the chat is a group space, otherwise the peer uid. */
    private String chatKey;
    private String chatName;
    private boolean isGroup;

    /** The Firestore space id that holds this conversation. */
    private String spaceId;
    private String peerUid;

    private TextView btnChatBack, tvChatEmoji, tvChatName, tvChatEmail, tvChatBalancePill;
    private NestedScrollView scrollChat;
    private LinearLayout layoutChatMessages;

    private EditText etAmountInput, etChatMessage;
    private TextView btnChatPay, btnChatRequest, btnChatQuery, btnChatSend;

    private ListenerRegistration chatListener;
    private ListenerRegistration settlementListener;

    /** settlementId -> status, so cards re-render when the payee responds. */
    private final Map<String, String> settlementStatus = new HashMap<>();
    /** settlementId -> toUid, so we know who is allowed to confirm. */
    private final Map<String, String> settlementPayee = new HashMap<>();

    private boolean spaceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        chatKey = getIntent().getStringExtra("chat_id");
        if (chatKey == null) chatKey = getIntent().getStringExtra("person_id");
        chatName = getIntent().getStringExtra("person_name");
        isGroup = getIntent().getBooleanExtra("is_group", false);

        if (chatName == null || chatName.isEmpty()) chatName = "Chat";
        if (chatKey == null || chatKey.isEmpty()) {
            Toast.makeText(this, "This conversation could not be opened", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        bindViews();

        tvChatName.setText(chatName);
        btnChatBack.setOnClickListener(v -> finish());
        btnChatSend.setOnClickListener(v -> sendTextMessage());
        btnChatPay.setOnClickListener(v -> sendPayment());
        btnChatRequest.setOnClickListener(v -> sendRequest());
        btnChatQuery.setOnClickListener(v -> showRaiseGrievanceDialog());

        setComposerEnabled(false);
        resolveSpace();
    }

    private void bindViews() {
        btnChatBack = findViewById(R.id.btn_chat_back);
        tvChatEmoji = findViewById(R.id.tv_chat_emoji);
        tvChatName = findViewById(R.id.tv_chat_name);
        tvChatEmail = findViewById(R.id.tv_chat_email);
        tvChatBalancePill = findViewById(R.id.tv_chat_balance_pill);

        scrollChat = findViewById(R.id.scroll_chat);
        layoutChatMessages = findViewById(R.id.layout_chat_messages);

        etAmountInput = findViewById(R.id.et_amount_input);
        etChatMessage = findViewById(R.id.et_chat_message);

        btnChatPay = findViewById(R.id.btn_chat_pay);
        btnChatRequest = findViewById(R.id.btn_chat_request);
        btnChatQuery = findViewById(R.id.btn_chat_query);
        btnChatSend = findViewById(R.id.btn_chat_send);
    }

    // ------------------------------------------------------------ space setup

    /**
     * Works out which space backs this screen and guarantees it exists.
     *
     * For a GROUP chat the space id is the group id. For a 1-to-1 chat the id is
     * derived from both uids and created on demand, which is the step that was
     * missing and made chat and payments look like they did nothing.
     */
    private void resolveSpace() {
        if (isGroup) {
            spaceId = chatKey;
            spaceReady = true;
            bindChatHeader();
            onSpaceResolved();
            return;
        }

        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            showBlocked("Please log in again");
            return;
        }

        // The intent may carry a uid (from the friends list) or a name (from a UI
        // shortcut). Resolve a name to a real uid before creating a space.
        if (looksLikeUid(chatKey) && TabRepository.getInstance().getPersonById(chatKey) != null) {
            startDirectSpace(chatKey);
            return;
        }

        TabRepository.getInstance().searchUsers(chatName, new FirebaseUsers.UserLookup() {
            @Override
            public void onFound(String uid) {
                startDirectSpace(uid);
            }

            @Override
            public void onNotFound() {
                showBlocked(chatName + " is not a TABS account yet. Add them as a friend first.");
            }

            @Override
            public void onError(String message) {
                showBlocked("Could not open this chat: " + message);
            }
        });
    }

    private void startDirectSpace(String uid) {
        peerUid = uid;
        TabRepository.getInstance().ensureDirectSpace(uid, new TabRepository.SpaceReadyListener() {
            @Override
            public void onSpaceReady(String id) {
                spaceId = id;
                spaceReady = true;
                bindChatHeader();
                onSpaceResolved();
            }

            @Override
            public void onSpaceUnavailable(String reason) {
                showBlocked(reason);
            }
        });
    }

    private void onSpaceResolved() {
        setComposerEnabled(true);
        startChatListener();
        listenToSettlements();
    }

    private void showBlocked(String reason) {
        spaceReady = false;
        setComposerEnabled(false);

        layoutChatMessages.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText("⚠️ " + reason
                + "\n\nOpen \"add friends\" to connect with them first.");
        tv.setTextSize(14f);
        tv.setTextColor(getResources().getColor(R.color.owe_red));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(32, 48, 32, 48);
        layoutChatMessages.addView(tv);

        tvChatBalancePill.setText("not connected");
        tvChatBalancePill.setBackgroundResource(R.drawable.bg_card_brut_red);
    }

    private void setComposerEnabled(boolean enabled) {
        btnChatSend.setEnabled(enabled);
        btnChatPay.setEnabled(enabled);
        btnChatRequest.setEnabled(enabled);
        btnChatQuery.setEnabled(enabled);
        etChatMessage.setEnabled(enabled);
        etAmountInput.setEnabled(enabled);

        float alpha = enabled ? 1f : 0.4f;
        btnChatSend.setAlpha(alpha);
        btnChatPay.setAlpha(alpha);
        btnChatRequest.setAlpha(alpha);
        btnChatQuery.setAlpha(alpha);
    }

    // ----------------------------------------------------------------- header

    private void bindChatHeader() {
        if (isGroup) {
            Group g = TabRepository.getInstance().getGroupById(chatKey);
            tvChatEmoji.setText(g != null ? g.getEmoji() : "👥");
            int memberCount = g != null ? g.getMembers().size() : 0;
            tvChatEmail.setText("Group pool · " + memberCount + " members");
            tvChatBalancePill.setText(g != null ? "spent ₹" + g.getTotalSpent() : "Group tab");
            tvChatBalancePill.setBackgroundResource(R.drawable.bg_pill_yellow);
            return;
        }

        Person p = TabRepository.getInstance().getPersonById(peerUid);

        tvChatEmoji.setText(p != null ? p.getEmoji() : "🙂");
        if (p != null && !TextUtils.isEmpty(p.getEmail())) {
            tvChatEmail.setText(p.getEmail());
            tvChatName.setText(p.getName());
        } else {
            tvChatEmail.setText("TABS member");
        }

        int net = p != null ? p.getNetAmount() : 0;  // already rupees
        if (net > 0) {
            tvChatBalancePill.setText("owes you ₹" + net);
            tvChatBalancePill.setBackgroundResource(R.drawable.bg_pill_lime);
        } else if (net < 0) {
            tvChatBalancePill.setText("you owe ₹" + Math.abs(net));
            tvChatBalancePill.setBackgroundResource(R.drawable.bg_pill_pink);
        } else {
            tvChatBalancePill.setText("all square ✨");
            tvChatBalancePill.setBackgroundResource(R.drawable.bg_pill_cream);
        }
    }

    // --------------------------------------------------------------- messages

    private void startChatListener() {
        chatListener = TabRepository.getInstance().listenToChat(spaceId, messages -> runOnUiThread(() -> {
            layoutChatMessages.removeAllViews();
            if (messages.isEmpty()) {
                addEmptyNotice();
            } else {
                for (SpaceMessage m : messages) {
                    renderMessage(m);
                }
            }
            scrollToBottom();
        }));
    }

    /** Watches settlement documents so payment cards show the real status. */
    private void listenToSettlements() {
        if (spaceId == null) return;
        settlementListener = FirebaseFirestore.getInstance()
                .collection("spaces").document(spaceId).collection("settlements")
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) return;
                    synchronized (settlementStatus) {
                        settlementStatus.clear();
                        settlementPayee.clear();
                        for (DocumentSnapshot doc : snapshots.getDocuments()) {
                            settlementStatus.put(doc.getId(), doc.getString("status"));
                            settlementPayee.put(doc.getId(), doc.getString("toUid"));
                        }
                    }
                    // Re-render so a confirmation appears immediately.
                    runOnUiThread(this::refreshMessages);
                });
    }

    private void refreshMessages() {
        if (spaceId == null) return;
        TabRepository.getInstance().loadMessages(spaceId, messages -> runOnUiThread(() -> {
            layoutChatMessages.removeAllViews();
            if (messages.isEmpty()) {
                addEmptyNotice();
            } else {
                for (SpaceMessage m : messages) {
                    renderMessage(m);
                }
            }
        }));
    }

    private void addEmptyNotice() {
        TextView tv = new TextView(this);
        tv.setText("👋 Start of your tab with " + chatName
                + ".\nMessages and payments sync here in real time.");
        tv.setTextSize(13f);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(32, 48, 32, 48);
        layoutChatMessages.addView(tv);
    }

    private void renderMessage(SpaceMessage m) {
        boolean isMe = m.getSenderUid() != null
                && m.getSenderUid().equalsIgnoreCase(TabRepository.getInstance().getCurrentUserId());

        String type = m.getType();
        if ("TEXT".equalsIgnoreCase(type)) {
            addTextBubble(displayNameFor(m), m.getText(), isMe);
        } else if ("PAYMENT".equalsIgnoreCase(type) || "SETTLEMENT".equalsIgnoreCase(type)) {
            addPaymentCard(m, isMe);
        } else if ("REQUEST".equalsIgnoreCase(type)) {
            addRequestCard(displayNameFor(m), m.getAmount(), m.getText(), isMe);
        } else if ("QUERY".equalsIgnoreCase(type)) {
            addQueryCard(displayNameFor(m), m.getText(), m.getReferenceId());
        } else if ("EXPENSE_SHARE".equalsIgnoreCase(type) || "EXPENSE".equalsIgnoreCase(type)) {
            addExpenseShareCard(displayNameFor(m), m.getAmount(), m.getText(), m.getReferenceId());
        }
    }

    private String displayNameFor(SpaceMessage m) {
        if (m.getSenderUid() != null
                && m.getSenderUid().equalsIgnoreCase(TabRepository.getInstance().getCurrentUserId())) {
            return "You";
        }
        String name = m.getSenderName();
        return TextUtils.isEmpty(name) ? "Them" : name;
    }

    private void sendTextMessage() {
        String msg = etChatMessage.getText().toString().trim();
        if (msg.isEmpty()) return;
        if (!spaceReady) {
            Toast.makeText(this, "This conversation is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        etChatMessage.setText("");
        TabRepository.getInstance().sendMessage(spaceId, msg, "TEXT", 0, "",
                new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() { /* the listener renders it */ }

                    @Override
                    public void onFailure(String message) {
                        Toast.makeText(ChatActivity.this,
                                "Message not sent: " + message, Toast.LENGTH_LONG).show();
                        etChatMessage.setText(msg);
                    }
                });
    }

    /** Proposes a payment TO the other person. They confirm it, not you. */
    private void sendPayment() {
        if (!spaceReady) {
            Toast.makeText(this, "This conversation is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        String payee = isGroup ? null : peerUid;
        if (isGroup) {
            Toast.makeText(this, "Pick one person to pay in a group chat", Toast.LENGTH_SHORT).show();
            return;
        }

        long amountPaise;
        try {
            amountPaise = Money.parsePaise(etAmountInput.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage() != null
                    ? e.getMessage() : "Enter a valid amount, e.g. 250 or 99.50",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (amountPaise <= 0) {
            Toast.makeText(this, "Enter an amount greater than ₹0", Toast.LENGTH_SHORT).show();
            return;
        }

        etAmountInput.setText("");
        TabRepository.getInstance().createSettlement(spaceId, payee, amountPaise,
                new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(ChatActivity.this,
                                "Payment of " + Money.formatInr(amountPaise)
                                        + " sent — waiting for them to confirm ✅",
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFailure(String message) {
                        Toast.makeText(ChatActivity.this,
                                "Payment not recorded: " + message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void sendRequest() {
        if (!spaceReady) {
            Toast.makeText(this, "This conversation is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        long amountPaise;
        try {
            amountPaise = Money.parsePaise(etAmountInput.getText().toString());
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage() != null
                    ? e.getMessage() : "Enter a valid amount to request",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (amountPaise <= 0) {
            Toast.makeText(this, "Enter an amount greater than ₹0", Toast.LENGTH_SHORT).show();
            return;
        }

        String note = etChatMessage.getText().toString().trim();
        if (note.isEmpty()) note = "payment requested";

        etAmountInput.setText("");
        etChatMessage.setText("");

        final String requestNote = note;
        TabRepository.getInstance().sendMessage(spaceId, requestNote, "REQUEST",
                (int) (amountPaise / 100), "", new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(ChatActivity.this,
                                "Requested " + Money.formatInr(amountPaise), Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(String message) {
                        Toast.makeText(ChatActivity.this,
                                "Request not sent: " + message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ------------------------------------------------------------------ cards

    private void addTextBubble(String sender, String text, boolean isRight) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        if (!isRight) {
            TextView tvSender = new TextView(this);
            tvSender.setText(sender);
            tvSender.setTextSize(10f);
            tvSender.setTextColor(getResources().getColor(R.color.text_secondary));
            tvSender.setPadding(8, 0, 0, 2);
            row.addView(tvSender);
        }

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(14f);
        bubble.setTextColor(getResources().getColor(R.color.ink));
        bubble.setPadding(32, 20, 32, 20);
        bubble.setBackgroundResource(isRight ? R.drawable.bg_pill_lime : R.drawable.bg_pill_outlined);
        row.addView(bubble);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = isRight ? Gravity.END : Gravity.START;
        params.setMargins(0, 0, 0, 16);
        row.setLayoutParams(params);

        layoutChatMessages.addView(row);
    }

    /**
     * A payment card. The status comes from the real settlement document, and the
     * confirm / dispute buttons only appear for the person being paid.
     */
    private void addPaymentCard(SpaceMessage m, boolean isMe) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_ledger_expense, layoutChatMessages, false);
        TextView tvDate = card.findViewById(R.id.tv_date_header);
        TextView tvTitle = card.findViewById(R.id.tv_title);
        TextView tvTotal = card.findViewById(R.id.tv_total);
        TextView tvShare = card.findViewById(R.id.tv_share_info);
        LinearLayout cardBubble = card.findViewById(R.id.layout_card_bubble);

        final String settlementId = m.getReferenceId();
        String status = settlementId != null ? settlementStatus.get(settlementId) : null;
        if (status == null) status = "PENDING";
        String payeeUid = settlementId != null ? settlementPayee.get(settlementId) : null;

        tvDate.setText(isMe ? "You proposed a payment" : displayNameFor(m) + " proposed a payment");
        tvTitle.setText(m.getText());
        tvTotal.setText("₹" + m.getAmount());

        final String currentStatus = status;
        applySettlementLook(tvShare, cardBubble, currentStatus);

        String myUid = TabRepository.getInstance().getCurrentUserId();
        boolean iAmPayee = myUid != null && myUid.equals(payeeUid);

        // Only the payee can confirm or dispute, and only while it is pending.
        if (iAmPayee && "PENDING".equalsIgnoreCase(status)) {
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, 8, 0, 0);

            TextView btnConfirm = new TextView(this);
            btnConfirm.setText("✓ Confirm received");
            btnConfirm.setTextSize(12f);
            btnConfirm.setPadding(20, 8, 20, 8);
            btnConfirm.setBackgroundResource(R.drawable.bg_pill_lime);
            btnConfirm.setOnClickListener(v -> decideSettlement(settlementId, "CONFIRMED", tvShare, cardBubble));

            TextView btnDispute = new TextView(this);
            btnDispute.setText("✕ Not received");
            btnDispute.setTextSize(12f);
            btnDispute.setPadding(20, 8, 20, 8);
            btnDispute.setBackgroundResource(R.drawable.bg_pill_pink);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(12, 0, 0, 0);
            btnDispute.setLayoutParams(lp);
            btnDispute.setOnClickListener(v -> decideSettlement(settlementId, "DISPUTED", tvShare, cardBubble));

            btnRow.addView(btnConfirm);
            btnRow.addView(btnDispute);
            cardBubble.addView(btnRow);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = isMe ? Gravity.END : Gravity.START;
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);

        layoutChatMessages.addView(card);
    }

    private void applySettlementLook(TextView tvShare, LinearLayout cardBubble, String status) {
        if ("CONFIRMED".equalsIgnoreCase(status)) {
            tvShare.setText("✓ Confirmed & settled");
            cardBubble.setBackgroundResource(R.drawable.bg_card_brut_lime);
        } else if ("DISPUTED".equalsIgnoreCase(status)) {
            tvShare.setText("✕ Marked as not received");
            cardBubble.setBackgroundResource(R.drawable.bg_card_brut_red);
        } else if ("CANCELLED".equalsIgnoreCase(status)) {
            tvShare.setText("— Withdrawn");
            cardBubble.setBackgroundResource(R.drawable.bg_card_brut_paper);
        } else {
            tvShare.setText("⏳ Waiting for confirmation");
            cardBubble.setBackgroundResource(R.drawable.bg_card_brut_yellow);
        }
    }

    private void decideSettlement(String settlementId, String status,
                                  TextView tvShare, LinearLayout cardBubble) {
        if (settlementId == null || settlementId.isEmpty()) {
            Toast.makeText(this, "This payment could not be found", Toast.LENGTH_SHORT).show();
            return;
        }

        TabRepository.getInstance().updateSettlementStatus(spaceId, settlementId, status,
                new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() {
                        applySettlementLook(tvShare, cardBubble, status);
                        Toast.makeText(ChatActivity.this,
                                "CONFIRMED".equals(status)
                                        ? "Confirmed — balances updated ✅"
                                        : "Marked as not received. Balances unchanged.",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(String message) {
                        Toast.makeText(ChatActivity.this,
                                "Could not update: " + message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void addRequestCard(String sender, int amount, String note, boolean isMe) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_ledger_expense, layoutChatMessages, false);
        TextView tvDate = card.findViewById(R.id.tv_date_header);
        TextView tvTitle = card.findViewById(R.id.tv_title);
        TextView tvTotal = card.findViewById(R.id.tv_total);
        TextView tvShare = card.findViewById(R.id.tv_share_info);
        LinearLayout cardBubble = card.findViewById(R.id.layout_card_bubble);

        tvDate.setText(isMe ? "You requested" : sender + " requested");
        tvTitle.setText(note);
        tvTotal.setText("₹" + amount);
        tvShare.setText("⌛ waiting for a response");
        cardBubble.setBackgroundResource(R.drawable.bg_card_brut_yellow);

        // The person being asked can pay straight from the card.
        if (!isMe && !isGroup && peerUid != null) {
            TextView btnPayNow = new TextView(this);
            btnPayNow.setText("pay ₹" + amount);
            btnPayNow.setTextSize(12f);
            btnPayNow.setPadding(20, 8, 20, 8);
            btnPayNow.setBackgroundResource(R.drawable.bg_pill_lime);
            btnPayNow.setOnClickListener(v -> {
                etAmountInput.setText(String.valueOf(amount));
                sendPayment();
            });
            cardBubble.addView(btnPayNow);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = isMe ? Gravity.END : Gravity.START;
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);

        layoutChatMessages.addView(card);
    }

    private void addQueryCard(String sender, String text, String expenseId) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_ledger_offset, layoutChatMessages, false);
        TextView tvHeader = card.findViewById(R.id.tv_offset_header);
        TextView tvTitles = card.findViewById(R.id.tv_offset_titles);
        TextView tvDetail = card.findViewById(R.id.tv_offset_detail);

        tvHeader.setText("⚖️ TABS COURT QUERY");
        tvTitles.setText("By " + sender);
        tvDetail.setText(text);
        card.setBackgroundResource(R.drawable.bg_card_brut_red);

        card.setOnClickListener(v -> showResolveDisputeDialog(expenseId, ""));
        layoutChatMessages.addView(card);
    }

    private void addExpenseShareCard(String sender, int amount, String title, String expenseId) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_ledger_expense, layoutChatMessages, false);
        TextView tvDate = card.findViewById(R.id.tv_date_header);
        TextView tvTitle = card.findViewById(R.id.tv_title);
        TextView tvTotal = card.findViewById(R.id.tv_total);
        TextView tvShare = card.findViewById(R.id.tv_share_info);
        LinearLayout cardBubble = card.findViewById(R.id.layout_card_bubble);

        tvDate.setText("Expense added by " + sender + " · tap to query");
        tvTitle.setText(title);
        tvTotal.setText("₹" + amount);
        tvShare.setText("Tap to raise a query");
        cardBubble.setBackgroundResource(R.drawable.bg_card_brut_lime);

        card.setOnClickListener(v -> showRaiseQueryForExpenseDialog(expenseId, title));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);

        layoutChatMessages.addView(card);
    }

    // --------------------------------------------------------------- dialogs

    private void showRaiseQueryForExpenseDialog(String expenseId, String expenseTitle) {
        if (!spaceReady) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚖️ Query '" + expenseTitle + "'");

        EditText etReason = new EditText(this);
        etReason.setHint("Why are you disputing this expense?");
        etReason.setBackgroundResource(R.drawable.bg_pill_outlined);
        etReason.setPadding(24, 16, 24, 16);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 16);
        container.addView(etReason);

        builder.setView(container);
        builder.setPositiveButton("Submit query", (dialog, which) -> {
            String reason = etReason.getText().toString().trim();
            if (reason.isEmpty()) reason = "Disputed split";
            TabRepository.getInstance().raiseDispute(spaceId, expenseId, expenseTitle, reason);
            Toast.makeText(this, "Query submitted ⚖️", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showResolveDisputeDialog(String expenseId, String disputeId) {
        if (!spaceReady) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚖️ Resolve expense dispute");

        String[] options = {"Remove me from this split", "Adjust the amount", "Dismiss"};
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                TabRepository.getInstance().resolveDisputeAction(spaceId, expenseId, disputeId, "REMOVE", 0);
                Toast.makeText(this, "Removed you from the split ✅", Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                showAdjustAmountDialog(expenseId, disputeId);
            } else {
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private void showAdjustAmountDialog(String expenseId, String disputeId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Adjust expense amount");

        EditText etNewAmount = new EditText(this);
        etNewAmount.setHint("New amount in ₹ (e.g. 1200)");
        etNewAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etNewAmount.setBackgroundResource(R.drawable.bg_pill_outlined);
        etNewAmount.setPadding(24, 16, 24, 16);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 16);
        container.addView(etNewAmount);

        builder.setView(container);
        builder.setPositiveButton("Save & resolve", (dialog, which) -> {
            try {
                long newPaise = Money.parsePaise(etNewAmount.getText().toString());
                TabRepository.getInstance().resolveDisputeAction(spaceId, expenseId, disputeId, "ADJUST", newPaise);
                Toast.makeText(this, "Amount adjusted and split recomputed ✅", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage() != null
                        ? e.getMessage() : "Invalid amount", Toast.LENGTH_LONG).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showRaiseGrievanceDialog() {
        showRaiseQueryForExpenseDialog("general_expense", "General query");
    }

    // ---------------------------------------------------------------- helpers

    private void scrollToBottom() {
        scrollChat.post(() -> scrollChat.fullScroll(View.FOCUS_DOWN));
    }

    /** Firestore ids are 20 chars of [A-Za-z0-9]; names never look like that. */
    private boolean looksLikeUid(String value) {
        if (value == null || value.length() < 15 || value.length() > 40) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (spaceReady) {
            bindChatHeader();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (chatListener != null) chatListener.remove();
        if (settlementListener != null) settlementListener.remove();
    }
}
