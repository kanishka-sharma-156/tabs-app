package com.example.tab_able;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.adapters.LedgerAdapter;
import com.example.tab_able.data.LedgerItem;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.TabRepository;
import com.example.tab_able.logic.Money;

import java.util.List;

public class LedgerActivity extends AppCompatActivity implements TabRepository.DataChangeListener {

    private String personId;
    private Person person;
    private boolean loadingLedger = false;

    private TextView btnBack;
    private TextView tvPersonEmoji, tvPersonName, tvPersonTag;
    private LinearLayout cardNetBalance;
    private TextView tvBalanceDirection, tvBalanceAmount;
    private RecyclerView rvLedger;
    private LinearLayout layoutLedgerEmpty;
    private TextView tvEmptySubtitle;
    private TextView btnNudge, btnSettleUp;

    private LedgerAdapter ledgerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ledger);

        personId = getIntent().getStringExtra("person_id");
        if (personId == null || personId.isEmpty()) {
            Toast.makeText(this, "This person could not be opened", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        btnBack = findViewById(R.id.btn_back);
        tvPersonEmoji = findViewById(R.id.tv_person_emoji);
        tvPersonName = findViewById(R.id.tv_person_name);
        tvPersonTag = findViewById(R.id.tv_person_tag);
        cardNetBalance = findViewById(R.id.card_net_balance);
        tvBalanceDirection = findViewById(R.id.tv_balance_direction);
        tvBalanceAmount = findViewById(R.id.tv_balance_amount);
        rvLedger = findViewById(R.id.rv_ledger);
        layoutLedgerEmpty = findViewById(R.id.layout_ledger_empty);
        tvEmptySubtitle = findViewById(R.id.tv_empty_subtitle);
        btnNudge = findViewById(R.id.btn_nudge);
        btnSettleUp = findViewById(R.id.btn_settle_up);

        rvLedger.setLayoutManager(new LinearLayoutManager(this));

        btnBack.setOnClickListener(v -> finish());

        refreshData();

        TextView btnOpenChat = findViewById(R.id.btn_open_chat);
        btnOpenChat.setOnClickListener(v -> {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("chat_id", personId);
            intent.putExtra("person_id", personId);
            intent.putExtra("person_name", person != null ? person.getName() : "Friend");
            intent.putExtra("is_group", false);
            startActivity(intent);
        });

        btnNudge.setOnClickListener(v -> {
            if (person == null) return;
            person.incrementNudge();
            person.setTag("nudged " + person.getNudgeCount() + "x");
            tvPersonTag.setText(person.getTag());

            // A nudge is a real message in the shared space, so the other person
            // actually receives it.
            TabRepository.getInstance().ensureDirectSpace(personId, new TabRepository.SpaceReadyListener() {
                @Override
                public void onSpaceReady(String spaceId) {
                    TabRepository.getInstance().sendMessage(spaceId,
                            "Nudge: bro… the money? 👉👈", "TEXT", 0, "");
                }

                @Override
                public void onSpaceUnavailable(String reason) {
                    Toast.makeText(LedgerActivity.this,
                            "Could not send the nudge: " + reason, Toast.LENGTH_SHORT).show();
                }
            });
            Toast.makeText(this, "Nudged " + person.getName() + " 👉👈 \"bro… the money?\"",
                    Toast.LENGTH_SHORT).show();
        });

        // Settle up proposes a payment for the REAL outstanding balance rather
        // than a made-up figure, and the other person confirms it.
        btnSettleUp.setOnClickListener(v -> {
            if (person == null) return;

            // getNetAmount() is rupees; settlements must be written in paise.
            final long owedPaise = Math.abs((long) person.getNetAmount()) * 100L;
            if (owedPaise <= 0) {
                Toast.makeText(this, "You are all square with " + person.getName() + " ✨",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            TabRepository.getInstance().ensureDirectSpace(personId, new TabRepository.SpaceReadyListener() {
                @Override
                public void onSpaceReady(String spaceId) {
                    TabRepository.getInstance().createSettlement(spaceId, personId, owedPaise,
                            new com.example.tab_able.data.FirebaseUsers.ActionResult() {
                                @Override
                                public void onSuccess() {
                                    Toast.makeText(LedgerActivity.this,
                                            "Proposed settlement of " + Money.formatInr(owedPaise)
                                                    + " — waiting for " + person.getName() + " to confirm",
                                            Toast.LENGTH_LONG).show();
                                    refreshData();
                                }

                                @Override
                                public void onFailure(String message) {
                                    Toast.makeText(LedgerActivity.this,
                                            "Could not propose the settlement: " + message,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                }

                @Override
                public void onSpaceUnavailable(String reason) {
                    Toast.makeText(LedgerActivity.this,
                            "Could not open this tab: " + reason, Toast.LENGTH_SHORT).show();
                }
            });
        });

        TabRepository.getInstance().addDataChangeListener(this);
    }

    private void refreshData() {
        person = TabRepository.getInstance().getPersonById(personId);

        if (person != null) {
            tvPersonEmoji.setText(person.getEmoji());
            tvPersonName.setText(person.getName());
            tvPersonTag.setText(person.getTag());

            int net = person.getNetAmount();
            if (net > 0) {
                cardNetBalance.setBackgroundResource(R.drawable.bg_card_brut_lime);
                tvBalanceDirection.setText(person.getName() + " owes you");
                tvBalanceAmount.setText("₹" + net);
            } else if (net < 0) {
                cardNetBalance.setBackgroundResource(R.drawable.bg_card_brut_pink);
                tvBalanceDirection.setText("you owe " + person.getName());
                tvBalanceAmount.setText("₹" + Math.abs(net));
            } else {
                cardNetBalance.setBackgroundResource(R.drawable.bg_card_brut_paper);
                tvBalanceDirection.setText("all square with " + person.getName());
                tvBalanceAmount.setText("₹0");
            }
        }

        // The ledger is loaded from the real DIRECT space between the two people.
        if (loadingLedger) return;
        loadingLedger = true;

        TabRepository.getInstance().loadLedgerForPerson(personId, items -> runOnUiThread(() -> {
            loadingLedger = false;
            if (isFinishing() || isDestroyed()) return;

            if (items.isEmpty()) {
                rvLedger.setVisibility(View.GONE);
                layoutLedgerEmpty.setVisibility(View.VISIBLE);
                if (person != null) {
                    tvEmptySubtitle.setText("You and " + person.getName()
                            + " haven't split anything yet.");
                }
            } else {
                rvLedger.setVisibility(View.VISIBLE);
                layoutLedgerEmpty.setVisibility(View.GONE);
                if (ledgerAdapter == null) {
                    ledgerAdapter = new LedgerAdapter(this, items,
                            person != null ? person.getName() : "Friend");
                    rvLedger.setAdapter(ledgerAdapter);
                } else {
                    ledgerAdapter.updateData(items);
                }
            }
        }));
    }

    @Override
    public void onDataChanged() {
        refreshData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TabRepository.getInstance().removeDataChangeListener(this);
    }
}
