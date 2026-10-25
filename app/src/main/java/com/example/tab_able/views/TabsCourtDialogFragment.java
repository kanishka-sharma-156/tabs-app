package com.example.tab_able.views;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.tab_able.R;
import com.example.tab_able.data.TabRepository;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class TabsCourtDialogFragment extends BottomSheetDialogFragment {

    public interface OnDisputeResolvedListener {
        void onResolved();
    }

    private String groupId;
    private String groupName;
    private String disputeId;
    private String disputeTitle;
    private String disputeReason;

    private OnDisputeResolvedListener listener;

    public static TabsCourtDialogFragment newInstance(String groupId, String groupName, String disputeId, String disputeTitle, String disputeReason) {
        TabsCourtDialogFragment fragment = new TabsCourtDialogFragment();
        Bundle args = new Bundle();
        args.putString("group_id", groupId);
        args.putString("group_name", groupName);
        args.putString("dispute_id", disputeId);
        args.putString("dispute_title", disputeTitle);
        args.putString("dispute_reason", disputeReason);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnDisputeResolvedListener(OnDisputeResolvedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            groupId = getArguments().getString("group_id");
            groupName = getArguments().getString("group_name");
            disputeId = getArguments().getString("dispute_id");
            disputeTitle = getArguments().getString("dispute_title");
            disputeReason = getArguments().getString("dispute_reason");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_tabs_court, container, false);

        TextView tvGroupSubtitle = view.findViewById(R.id.tv_group_subtitle);
        TextView tvDisputeTitle = view.findViewById(R.id.tv_dispute_title);
        TextView tvDisputeReason = view.findViewById(R.id.tv_dispute_reason);
        TextView btnClose = view.findViewById(R.id.btn_close);

        TextView btnRemoveSplit = view.findViewById(R.id.btn_remove_split);
        TextView btnKeepSplit = view.findViewById(R.id.btn_keep_split);
        TextView btnGroupVote = view.findViewById(R.id.btn_group_vote);

        tvGroupSubtitle.setText(groupName);
        tvDisputeTitle.setText(disputeTitle);
        tvDisputeReason.setText(disputeReason);

        btnClose.setOnClickListener(v -> dismiss());

        btnRemoveSplit.setOnClickListener(v -> {
            TabRepository.getInstance().resolveDispute(groupId, disputeId);
            Toast.makeText(getContext(), "Person removed from split — dispute closed! 🎉", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onResolved();
            dismiss();
        });

        btnKeepSplit.setOnClickListener(v -> {
            TabRepository.getInstance().resolveDispute(groupId, disputeId);
            Toast.makeText(getContext(), "Split kept as is — dispute closed! 🤝", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onResolved();
            dismiss();
        });

        btnGroupVote.setOnClickListener(v -> {
            TabRepository.getInstance().resolveDispute(groupId, disputeId);
            Toast.makeText(getContext(), "Put to group vote 🗳️ — notifications sent!", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onResolved();
            dismiss();
        });

        return view;
    }
}
