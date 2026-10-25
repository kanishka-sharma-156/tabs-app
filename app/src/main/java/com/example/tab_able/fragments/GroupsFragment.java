package com.example.tab_able.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.ChatActivity;
import com.example.tab_able.R;
import com.example.tab_able.adapters.GroupAdapter;
import com.example.tab_able.data.Group;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.TabRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups tab plus the 1-to-1 splits list.
 *
 * Both views are driven entirely by Firestore-backed repository state. The splits
 * list shows the real per-person balances computed from shared expenses, and the
 * create-group dialog resolves typed usernames into real accounts.
 */
public class GroupsFragment extends Fragment implements TabRepository.DataChangeListener {

    private boolean isSplitsTab = false;

    private TextView tvGroupsCount;
    private TextView btnSubtabGroups, btnSubtabSplits;
    private RecyclerView rvGroups;
    private NestedScrollView layoutSplitsView;
    private LinearLayout layoutSplitsItems;
    private LinearLayout layoutEmptyState;
    private TextView btnStartGroup;

    private GroupAdapter groupAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_groups, container, false);

        tvGroupsCount = view.findViewById(R.id.tv_groups_count);
        btnSubtabGroups = view.findViewById(R.id.btn_subtab_groups);
        btnSubtabSplits = view.findViewById(R.id.btn_subtab_splits);

        rvGroups = view.findViewById(R.id.rv_groups);
        layoutSplitsView = view.findViewById(R.id.layout_splits_view);
        layoutSplitsItems = view.findViewById(R.id.layout_splits_items);

        layoutEmptyState = view.findViewById(R.id.layout_empty_state);
        btnStartGroup = view.findViewById(R.id.btn_start_group);

        rvGroups.setLayoutManager(new LinearLayoutManager(getContext()));
        groupAdapter = new GroupAdapter(getContext(), TabRepository.getInstance().getGroups());
        rvGroups.setAdapter(groupAdapter);

        btnSubtabGroups.setOnClickListener(v -> {
            isSplitsTab = false;
            updateSubtabUI();
        });

        btnSubtabSplits.setOnClickListener(v -> {
            isSplitsTab = true;
            updateSubtabUI();
        });

        btnStartGroup.setOnClickListener(v -> showCreateGroupDialog());

        updateSubtabUI();
        TabRepository.getInstance().addDataChangeListener(this);

        return view;
    }

    private void updateSubtabUI() {
        if (isSplitsTab) {
            btnSubtabSplits.setBackgroundResource(R.drawable.bg_pill_lime);
            btnSubtabGroups.setBackgroundResource(android.R.color.transparent);
        } else {
            btnSubtabGroups.setBackgroundResource(R.drawable.bg_pill_lime);
            btnSubtabSplits.setBackgroundResource(android.R.color.transparent);
        }
        updateVisibility();
    }

    /**
     * Members are entered as usernames. They are resolved to real uids through the
     * usernames lookup, so a group can only ever contain actual accounts.
     */
    private void showCreateGroupDialog() {
        if (getContext() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("➕ Create a group");

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        EditText etGroupName = new EditText(getContext());
        etGroupName.setHint("Group name (e.g. Goa Trip '26)");
        etGroupName.setBackgroundResource(R.drawable.bg_pill_outlined);
        etGroupName.setPadding(24, 16, 24, 16);
        layout.addView(etGroupName);

        EditText etEmoji = new EditText(getContext());
        etEmoji.setHint("Emoji (optional, e.g. 🏖️)");
        etEmoji.setBackgroundResource(R.drawable.bg_pill_outlined);
        etEmoji.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 16, 0, 0);
        etEmoji.setLayoutParams(lp);
        layout.addView(etEmoji);

        EditText etMembers = new EditText(getContext());
        etMembers.setHint("Usernames, comma separated (e.g. riya, aman)");
        etMembers.setBackgroundResource(R.drawable.bg_pill_outlined);
        etMembers.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.setMargins(0, 16, 0, 0);
        etMembers.setLayoutParams(lp2);
        layout.addView(etMembers);

        TextView hint = new TextView(getContext());
        hint.setText("Only people on TABS who have accepted your friend request can be added.");
        hint.setTextSize(11f);
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.setMargins(0, 12, 0, 0);
        hint.setLayoutParams(lp3);
        layout.addView(hint);

        builder.setView(layout);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String name = etGroupName.getText().toString().trim();
            String emoji = etEmoji.getText().toString().trim();
            String memStr = etMembers.getText().toString().trim();

            if (name.isEmpty()) {
                Toast.makeText(getContext(), "Give the group a name", Toast.LENGTH_SHORT).show();
                return;
            }
            if (emoji.isEmpty()) emoji = "👥";

            List<String> memberKeys = new ArrayList<>();
            if (!memStr.isEmpty()) {
                for (String part : memStr.split(",")) {
                    if (!part.trim().isEmpty()) memberKeys.add(part.trim());
                }
            }

            if (memberKeys.isEmpty()) {
                // A group of one is still useful as a personal pool.
                Toast.makeText(getContext(),
                        "Created \"" + name + "\" with just you. Add members by username any time.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(),
                        "Resolving members and creating \"" + name + "\"…", Toast.LENGTH_SHORT).show();
            }

            TabRepository.getInstance().createGroup(name, emoji, memberKeys);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void updateVisibility() {
        List<Group> grps = TabRepository.getInstance().getGroups();
        List<Person> people = TabRepository.getInstance().getAllPeople();

        boolean noGroups = grps.isEmpty();
        boolean noSplits = people.isEmpty();

        if (tvGroupsCount != null) {
            tvGroupsCount.setText(noGroups ? "" : grps.size() + " groups");
        }

        if (isSplitsTab) {
            rvGroups.setVisibility(View.GONE);

            if (noSplits) {
                layoutSplitsView.setVisibility(View.GONE);
                layoutEmptyState.setVisibility(View.VISIBLE);
            } else {
                layoutSplitsView.setVisibility(View.VISIBLE);
                layoutEmptyState.setVisibility(View.GONE);
                setupSplitsItems(people);
            }
        } else {
            layoutSplitsView.setVisibility(View.GONE);

            if (noGroups) {
                rvGroups.setVisibility(View.GONE);
                layoutEmptyState.setVisibility(View.VISIBLE);
            } else {
                rvGroups.setVisibility(View.VISIBLE);
                layoutEmptyState.setVisibility(View.GONE);
                if (groupAdapter != null) {
                    groupAdapter.updateData(grps);
                }
            }
        }
    }

    /**
     * Real 1-to-1 balances: one card per friend showing what the shared expenses
     * actually add up to. Tapping opens the person's ledger.
     */
    private void setupSplitsItems(List<Person> people) {
        if (getContext() == null) return;

        layoutSplitsItems.removeAllViews();
        Set<String> seen = new HashSet<>();

        for (Person p : people) {
            if (p.getId().isEmpty() || !seen.add(p.getId())) continue;

            int net = p.getNetAmount();
            String netText;
            boolean isOwed;
            if (net > 0) {
                netText = "+₹" + net;
                isOwed = true;
            } else if (net < 0) {
                netText = "-₹" + Math.abs(net);
                isOwed = false;
            } else {
                netText = "₹0";
                isOwed = true;
            }

            String subtitle = p.getTag().isEmpty() ? "no splits yet" : p.getTag();
            addSplitCard(p, subtitle, netText, isOwed);
        }
    }

    private void addSplitCard(Person person, String subtitle, String net, boolean isOwed) {
        if (getContext() == null) return;

        View card = LayoutInflater.from(getContext())
                .inflate(R.layout.item_group, layoutSplitsItems, false);

        TextView tvEmoji = card.findViewById(R.id.tv_group_emoji);
        TextView tvName = card.findViewById(R.id.tv_group_name);
        TextView tvSpent = card.findViewById(R.id.tv_group_spent);
        TextView tvNet = card.findViewById(R.id.tv_group_net);

        tvEmoji.setText(person.getEmoji());
        tvName.setText(person.getName());
        tvSpent.setText(subtitle);
        tvNet.setText(net);
        tvNet.setTextColor(getResources().getColor(isOwed ? R.color.owed_green : R.color.owe_red));

        card.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), ChatActivity.class);
            intent.putExtra("person_id", person.getId());
            intent.putExtra("chat_id", person.getId());
            intent.putExtra("person_name", person.getName());
            intent.putExtra("is_group", false);
            startActivity(intent);
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 20);
        card.setLayoutParams(params);

        layoutSplitsItems.addView(card);
    }

    @Override
    public void onDataChanged() {
        if (isAdded()) {
            updateVisibility();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateVisibility();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        TabRepository.getInstance().removeDataChangeListener(this);
    }
}
