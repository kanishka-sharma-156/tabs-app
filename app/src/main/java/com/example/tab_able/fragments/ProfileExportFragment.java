package com.example.tab_able.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.AddFriendsActivity;
import com.example.tab_able.LoginActivity;
import com.example.tab_able.R;
import com.example.tab_able.adapters.ActivityAdapter;
import com.example.tab_able.data.CurrentUser;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.TabRepository;
import com.example.tab_able.logic.Money;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

/**
 * The "you" tab: your profile, what you still owe, a data export built from your
 * real balances, and log out.
 */
public class ProfileExportFragment extends Fragment implements TabRepository.DataChangeListener {

    private TextView tvProfileHandle, tvProfileEmoji, tvYouOweTotal, tvOweEmpty, tvExportSummary;
    private TextView tvActivityCount;
    private LinearLayout layoutYouOwe;
    private RecyclerView rvActivity;
    private ActivityAdapter activityAdapter;

    private TextView btnExportCsv, btnExportTxt;
    private TextView btnGoFriends, btnLogout;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile_export, container, false);

        tvProfileHandle = view.findViewById(R.id.tv_profile_handle);
        tvProfileEmoji = view.findViewById(R.id.tv_profile_emoji);
        tvYouOweTotal = view.findViewById(R.id.tv_you_owe_total);
        tvOweEmpty = view.findViewById(R.id.tv_owe_empty);
        layoutYouOwe = view.findViewById(R.id.layout_you_owe);
        tvExportSummary = view.findViewById(R.id.tv_export_summary);
        tvActivityCount = view.findViewById(R.id.tv_activity_count);

        btnExportCsv = view.findViewById(R.id.btn_export_csv);
        btnExportTxt = view.findViewById(R.id.btn_export_txt);
        btnGoFriends = view.findViewById(R.id.btn_go_friends);
        btnLogout = view.findViewById(R.id.btn_logout);

        rvActivity = view.findViewById(R.id.rv_activity);
        rvActivity.setLayoutManager(new LinearLayoutManager(getContext()));
        activityAdapter = new ActivityAdapter(getContext(),
                TabRepository.getInstance().getRecentActivities());
        rvActivity.setAdapter(activityAdapter);

        btnExportCsv.setOnClickListener(v -> exportBalances(true));
        btnExportTxt.setOnClickListener(v -> exportBalances(false));

        TextView btnBackup = view.findViewById(R.id.btn_backup_firebase);
        if (btnBackup != null) {
            btnBackup.setOnClickListener(v -> {
                Toast.makeText(getContext(), "Backing up to Firebase… ☁️", Toast.LENGTH_SHORT).show();
                TabRepository.getInstance().saveCloudBackup((success, message) ->
                        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show());
            });
        }

        if (btnGoFriends != null) {
            btnGoFriends.setOnClickListener(v ->
                    startActivity(new Intent(getContext(), AddFriendsActivity.class)));
        }

        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> confirmLogout());
        }

        TabRepository.getInstance().addDataChangeListener(this);
        refresh();

        return view;
    }

    private void refresh() {
        TabRepository repo = TabRepository.getInstance();

        if (tvProfileHandle != null) {
            String username = CurrentUser.getUsername();
            String email = CurrentUser.getEmail();
            String handle = username != null && !username.isEmpty()
                    ? "@" + username
                    : (email != null && !email.isEmpty() ? email : "signed in");
            tvProfileHandle.setText(repo.getCurrentUserName() + " · " + handle);
        }
        if (tvProfileEmoji != null) {
            tvProfileEmoji.setText("😎");
        }

        renderYouOwe(repo);
        renderActivity(repo);
    }

    /** "tabs to clear by you" — only people you actually owe money to. */
    private void renderYouOwe(TabRepository repo) {
        if (layoutYouOwe == null) return;

        layoutYouOwe.removeAllViews();
        List<Person> youOwe = repo.getPeopleYouOwe();

        if (tvYouOweTotal != null) {
            // getTotalYouOwe() and getNetAmount() are already rupees.
            tvYouOweTotal.setText("₹" + repo.getTotalYouOwe());
        }

        if (youOwe.isEmpty()) {
            if (tvOweEmpty != null) tvOweEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (tvOweEmpty != null) tvOweEmpty.setVisibility(View.GONE);

        for (Person p : youOwe) {
            if (getContext() == null) return;

            View row = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_person_row, layoutYouOwe, false);
            layoutYouOwe.addView(row);

            TextView tvEmoji = row.findViewById(R.id.tv_row_emoji);
            TextView tvName = row.findViewById(R.id.tv_row_name);
            TextView tvSub = row.findViewById(R.id.tv_row_sub);
            TextView btnPay = row.findViewById(R.id.btn_row_primary);

            tvEmoji.setText(p.getEmoji());
            tvName.setText(p.getName());
            tvSub.setVisibility(View.VISIBLE);
            tvSub.setText("₹" + Math.abs(p.getNetAmount()) + " outstanding");

            btnPay.setText("settle up");
            btnPay.setOnClickListener(v -> proposeSettlement(p));
        }
    }

    /**
     * "Settle up" creates a real settlement in the shared space, which the other
     * person then has to confirm before your balance actually moves.
     */
    private void proposeSettlement(Person person) {
        // getNetAmount() is rupees; settlements are written in paise.
        final long owedPaise = Math.abs((long) person.getNetAmount()) * 100L;
        if (owedPaise <= 0) {
            Toast.makeText(getContext(), "Nothing outstanding with " + person.getName(),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        TabRepository.getInstance().ensureDirectSpace(person.getId(),
                new TabRepository.SpaceReadyListener() {
                    @Override
                    public void onSpaceReady(String spaceId) {
                        TabRepository.getInstance().createSettlement(spaceId, person.getId(), owedPaise,
                                new com.example.tab_able.data.FirebaseUsers.ActionResult() {
                                    @Override
                                    public void onSuccess() {
                                        Toast.makeText(getContext(),
                                                "Proposed " + Money.formatInr(owedPaise) + " to "
                                                        + person.getName() + " — they must confirm it",
                                                Toast.LENGTH_LONG).show();
                                    }

                                    @Override
                                    public void onFailure(String message) {
                                        Toast.makeText(getContext(),
                                                "Could not send: " + message, Toast.LENGTH_LONG).show();
                                    }
                                });
                    }

                    @Override
                    public void onSpaceUnavailable(String reason) {
                        Toast.makeText(getContext(),
                                "Could not open this tab: " + reason, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void renderActivity(TabRepository repo) {
        List<String> activities = repo.getRecentActivities();

        if (activityAdapter != null) {
            activityAdapter.updateData(activities);
        }
        if (tvActivityCount != null) {
            tvActivityCount.setText(activities.isEmpty() ? "nothing yet" : activities.size() + " recent");
        }
    }

    /**
     * Exports the real balances. The previous version emitted a fixed list of
     * invented people, so this now reflects whatever is actually in Firestore.
     */
    private void exportBalances(boolean asCsv) {
        TabRepository repo = TabRepository.getInstance();
        List<Person> all = repo.getAllPeople();

        if (all.isEmpty()) {
            Toast.makeText(getContext(),
                    "Nothing to export yet — add a friend and split something first",
                    Toast.LENGTH_LONG).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (asCsv) {
            sb.append("Name,Username,Direction,Amount (INR),Status\n");
            for (Person p : all) {
                sb.append(csv(p.getName())).append(',')
                        .append(csv(p.getEmail())).append(',')
                        .append(p.getNetAmount() > 0 ? "Owes you"
                                : (p.getNetAmount() < 0 ? "You owe" : "Settled")).append(',')
                        .append(Math.abs(p.getNetAmount())).append(',')
                        .append(csv(p.getTag()))
                        .append('\n');
            }
        } else {
            sb.append("TABS — balance receipt 🧾\n")
                    .append("account: ").append(repo.getCurrentUserName()).append('\n')
                    .append("generated: ").append(new java.util.Date().toString()).append("\n\n");
            for (Person p : all) {
                String direction = p.getNetAmount() > 0 ? "owes you"
                        : (p.getNetAmount() < 0 ? "you owe " : "settled  ");
                sb.append(String.format("%-20s %s ₹%d%n",
                        p.getName(), direction, Math.abs(p.getNetAmount())));
            }
            sb.append("\nOwed to you: ₹").append(repo.getTotalOwedToYou());
            sb.append("\nYou owe:     ₹").append(repo.getTotalYouOwe());
        }

        shareExport(asCsv ? "tabs-balances.csv" : "tabs-balances.txt", sb.toString());

        if (tvExportSummary != null) {
            tvExportSummary.setText(all.size() + " people · owed to you ₹"
                    + repo.getTotalOwedToYou());
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        String cleaned = value.replace(",", " ");
        return "\"" + cleaned + "\"";
    }

    private void shareExport(String filename, String content) {
        if (getContext() == null) return;

        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, content);
        sendIntent.setType("text/plain");

        startActivity(Intent.createChooser(sendIntent, "Export " + filename));
        Toast.makeText(getContext(), filename + " ready to share 📊", Toast.LENGTH_SHORT).show();
    }

    private void confirmLogout() {
        if (getContext() == null) return;

        new AlertDialog.Builder(getContext())
                .setTitle("Log out?")
                .setMessage("Your data stays safely in Firebase. Log back in any time.")
                .setPositiveButton("Log out", (dialog, which) -> performLogout())
                .setNegativeButton("Stay", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void performLogout() {
        FirebaseAuth.getInstance().signOut();
        TabRepository.getInstance().clearData();

        Toast.makeText(getContext(), "Logged out 👋", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(getContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);

        if (getActivity() != null) getActivity().finish();
    }

    @Override
    public void onDataChanged() {
        if (isAdded()) refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        TabRepository.getInstance().removeDataChangeListener(this);
    }
}
