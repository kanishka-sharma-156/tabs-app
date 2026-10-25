package com.example.tab_able.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.MainActivity;
import com.example.tab_able.R;
import com.example.tab_able.adapters.GroupAdapter;
import com.example.tab_able.adapters.PeopleAdapter;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.TabRepository;

import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment implements TabRepository.DataChangeListener {

    private boolean isOwedSelected = true;

    private TextView btnToggleOwed, btnToggleOwe;
    private LinearLayout cardTotal;
    private TextView tvTotalSubtitle, tvTotalAmount, tvTotalCaption;
    private TextView btnSeeAllGroups;

    private RecyclerView rvPeople, rvGroups;
    private PeopleAdapter peopleAdapter;
    private GroupAdapter groupAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        btnToggleOwed = view.findViewById(R.id.btn_toggle_owed);
        btnToggleOwe = view.findViewById(R.id.btn_toggle_owe);
        cardTotal = view.findViewById(R.id.card_total);
        tvTotalSubtitle = view.findViewById(R.id.tv_total_subtitle);
        tvTotalAmount = view.findViewById(R.id.tv_total_amount);
        tvTotalCaption = view.findViewById(R.id.tv_total_caption);
        btnSeeAllGroups = view.findViewById(R.id.btn_see_all_groups);

        rvPeople = view.findViewById(R.id.rv_people);
        rvGroups = view.findViewById(R.id.rv_groups);

        rvPeople.setLayoutManager(new LinearLayoutManager(getContext()));
        rvGroups.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));

        TabRepository repo = TabRepository.getInstance();

        List<Person> people = isOwedSelected ? repo.getPeopleOwedToYou() : repo.getPeopleYouOwe();
        peopleAdapter = new PeopleAdapter(getContext(), people, isOwedSelected);
        rvPeople.setAdapter(peopleAdapter);

        groupAdapter = new GroupAdapter(getContext(), repo.getGroups());
        rvGroups.setAdapter(groupAdapter);

        btnToggleOwed.setOnClickListener(v -> {
            if (!isOwedSelected) {
                isOwedSelected = true;
                updateToggleUI();
            }
        });

        btnToggleOwe.setOnClickListener(v -> {
            if (isOwedSelected) {
                isOwedSelected = false;
                updateToggleUI();
            }
        });

        btnSeeAllGroups.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).selectTab(1); // Groups Tab
            }
        });

        updateToggleUI();

        TabRepository.getInstance().addDataChangeListener(this);

        return view;
    }

    private void updateToggleUI() {
        TabRepository repo = TabRepository.getInstance();
        if (isOwedSelected) {
            btnToggleOwed.setBackgroundResource(R.drawable.bg_pill_lime);
            btnToggleOwe.setBackgroundResource(android.R.color.transparent);
            cardTotal.setBackgroundResource(R.drawable.bg_card_brut_lime);

            tvTotalSubtitle.setText("your friends owe you");
            tvTotalAmount.setText("₹" + String.format(Locale.US, "%,d", repo.getTotalOwedToYou()));
            tvTotalCaption.setText("time to become a lil annoying 😇");

            if (peopleAdapter != null) {
                peopleAdapter.updateData(repo.getPeopleOwedToYou(), true);
            }
        } else {
            btnToggleOwed.setBackgroundResource(android.R.color.transparent);
            btnToggleOwe.setBackgroundResource(R.drawable.bg_pill_pink);
            cardTotal.setBackgroundResource(R.drawable.bg_card_brut_pink);

            tvTotalSubtitle.setText("you owe your friends");
            tvTotalAmount.setText("₹" + String.format(Locale.US, "%,d", repo.getTotalYouOwe()));
            tvTotalCaption.setText("pay up bestie, no cap 💸");

            if (peopleAdapter != null) {
                peopleAdapter.updateData(repo.getPeopleYouOwe(), false);
            }
        }
        if (groupAdapter != null) {
            groupAdapter.updateData(repo.getGroups());
        }
    }

    @Override
    public void onDataChanged() {
        if (isAdded()) {
            updateToggleUI();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateToggleUI();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        TabRepository.getInstance().removeDataChangeListener(this);
    }
}
