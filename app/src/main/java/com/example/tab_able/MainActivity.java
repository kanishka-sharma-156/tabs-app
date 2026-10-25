package com.example.tab_able;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.tab_able.data.TabRepository;
import com.example.tab_able.fragments.AddExpenseFragment;
import com.example.tab_able.fragments.DashboardFragment;
import com.example.tab_able.fragments.GroupsFragment;
import com.example.tab_able.fragments.ProfileExportFragment;
import com.example.tab_able.fragments.TripMapFragment;
import com.google.firebase.auth.FirebaseAuth;

public class MainActivity extends AppCompatActivity {

    private LinearLayout tabHome, tabGroups, tabAdd, tabTrip, tabMe;
    private TextView iconHome, iconGroups, iconAdd, iconTrip, iconMe;
    private TextView labelHome, labelGroups, labelAdd, labelTrip, labelMe;

    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Never show the dashboard to a signed-out user. The repository's identity
        // and every Firestore listener depend on a real authenticated account.
        if (FirebaseAuth.getInstance().getCurrentUser() == null
                || TextUtils.isEmpty(TabRepository.getInstance().getCurrentUserId())) {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        tabHome = findViewById(R.id.tab_home);
        tabGroups = findViewById(R.id.tab_groups);
        tabAdd = findViewById(R.id.tab_add);
        tabTrip = findViewById(R.id.tab_trip);
        tabMe = findViewById(R.id.tab_me);

        iconHome = findViewById(R.id.icon_home);
        iconGroups = findViewById(R.id.icon_groups);
        iconAdd = findViewById(R.id.icon_add);
        iconTrip = findViewById(R.id.icon_trip);
        iconMe = findViewById(R.id.icon_me);

        labelHome = findViewById(R.id.label_home);
        labelGroups = findViewById(R.id.label_groups);
        labelAdd = findViewById(R.id.label_add);
        labelTrip = findViewById(R.id.label_trip);
        labelMe = findViewById(R.id.label_me);

        tabHome.setOnClickListener(v -> selectTab(0));
        tabGroups.setOnClickListener(v -> selectTab(1));
        tabAdd.setOnClickListener(v -> selectTab(2));
        tabTrip.setOnClickListener(v -> selectTab(3));
        tabMe.setOnClickListener(v -> selectTab(4));

        if (savedInstanceState == null) {
            selectTab(0);
        }
    }

    public void selectTab(int tabIndex) {
        currentTab = tabIndex;
        Fragment fragment;

        switch (tabIndex) {
            case 1:
                fragment = new GroupsFragment();
                break;
            case 2:
                fragment = new AddExpenseFragment();
                break;
            case 3:
                fragment = new TripMapFragment();
                break;
            case 4:
                fragment = new ProfileExportFragment();
                break;
            case 0:
            default:
                fragment = new DashboardFragment();
                break;
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();

        updateBottomNavUI(tabIndex);
    }

    private void updateBottomNavUI(int activeTab) {
        // Reset all icon backgrounds & text colors
        iconHome.setBackgroundResource(android.R.color.transparent);
        iconGroups.setBackgroundResource(android.R.color.transparent);
        iconAdd.setBackgroundResource(R.drawable.bg_pill_lime);
        iconTrip.setBackgroundResource(android.R.color.transparent);
        iconMe.setBackgroundResource(android.R.color.transparent);

        labelHome.setTextColor(getResources().getColor(R.color.text_secondary));
        labelGroups.setTextColor(getResources().getColor(R.color.text_secondary));
        labelAdd.setTextColor(getResources().getColor(R.color.text_secondary));
        labelTrip.setTextColor(getResources().getColor(R.color.text_secondary));
        labelMe.setTextColor(getResources().getColor(R.color.text_secondary));

        int inkColor = getResources().getColor(R.color.ink);

        switch (activeTab) {
            case 0:
                iconHome.setBackgroundResource(R.drawable.bg_pill_lime);
                labelHome.setTextColor(inkColor);
                break;
            case 1:
                iconGroups.setBackgroundResource(R.drawable.bg_pill_lime);
                labelGroups.setTextColor(inkColor);
                break;
            case 2:
                iconAdd.setBackgroundResource(R.drawable.bg_pill_lime);
                labelAdd.setTextColor(inkColor);
                break;
            case 3:
                iconTrip.setBackgroundResource(R.drawable.bg_pill_lime);
                labelTrip.setTextColor(inkColor);
                break;
            case 4:
                iconMe.setBackgroundResource(R.drawable.bg_pill_lime);
                labelMe.setTextColor(inkColor);
                break;
        }
    }
}
