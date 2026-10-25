package com.example.tab_able.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.ChatActivity;
import com.example.tab_able.R;
import com.example.tab_able.data.Group;
import com.example.tab_able.data.TabRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.GroupViewHolder> {

    private Context context;
    private List<Group> groups;
    private final Set<String> expandedGroupIds = new HashSet<>();

    public GroupAdapter(Context context, List<Group> groups) {
        this.context = context;
        this.groups = groups;
    }

    public void updateData(List<Group> groups) {
        this.groups = groups;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public GroupViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_group, parent, false);
        return new GroupViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
        Group group = groups.get(position);
        holder.tvEmoji.setText(group.getEmoji());
        holder.tvName.setText(group.getName());
        holder.tvSpent.setText("total spent ₹" + String.format("%,d", group.getTotalSpent()));

        if (group.getYourNet() > 0) {
            holder.tvNet.setText("+₹" + group.getYourNet());
            holder.tvNet.setTextColor(context.getResources().getColor(R.color.owed_green));
        } else if (group.getYourNet() < 0) {
            holder.tvNet.setText("-₹" + Math.abs(group.getYourNet()));
            holder.tvNet.setTextColor(context.getResources().getColor(R.color.owe_red));
        } else {
            holder.tvNet.setText("square");
            holder.tvNet.setTextColor(context.getResources().getColor(R.color.ink));
        }

        holder.layoutDisputes.removeAllViews();

        boolean isExpanded = expandedGroupIds.contains(group.getId());
        holder.layoutMembers.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        holder.layoutMemberPills.removeAllViews();

        for (String member : group.getMembers()) {
            TextView pill = new TextView(context);
            pill.setText(member);
            pill.setTextColor(context.getResources().getColor(R.color.ink));
            pill.setTextSize(12f);
            pill.setPadding(24, 8, 24, 8);
            pill.setBackgroundResource(R.drawable.bg_pill_outlined);

            LinearLayout.LayoutParams pParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pParams.setMargins(0, 0, 12, 0);
            pill.setLayoutParams(pParams);

            TabRepository.getInstance().resolveUserName(member, resolvedName -> pill.setText(resolvedName));

            holder.layoutMemberPills.addView(pill);
        }

        // Add + invite pill
        TextView invitePill = new TextView(context);
        invitePill.setText("+ invite");
        invitePill.setTextColor(context.getResources().getColor(R.color.ink));
        invitePill.setTextSize(12f);
        invitePill.setPadding(24, 8, 24, 8);
        invitePill.setBackgroundResource(R.drawable.bg_dashed_box);
        invitePill.setOnClickListener(v -> Toast.makeText(context, "Invite link copied 🔗", Toast.LENGTH_SHORT).show());

        holder.layoutMemberPills.addView(invitePill);

        TextView btnGroupChat = holder.itemView.findViewById(R.id.btn_group_chat);
        if (btnGroupChat != null) {
            btnGroupChat.setOnClickListener(v -> {
                Intent intent = new Intent(context, ChatActivity.class);
                intent.putExtra("chat_id", group.getId());
                intent.putExtra("person_name", group.getName());
                intent.putExtra("is_group", true);
                context.startActivity(intent);
            });
        }

        holder.itemView.setOnClickListener(v -> {
            if (expandedGroupIds.contains(group.getId())) {
                expandedGroupIds.remove(group.getId());
            } else {
                expandedGroupIds.add(group.getId());
            }
            notifyItemChanged(position);
        });
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        TextView tvEmoji, tvName, tvSpent, tvNet;
        LinearLayout layoutDisputes, layoutMembers, layoutMemberPills;

        public GroupViewHolder(@NonNull View itemView) {
            super(itemView);
            tvEmoji = itemView.findViewById(R.id.tv_group_emoji);
            tvName = itemView.findViewById(R.id.tv_group_name);
            tvSpent = itemView.findViewById(R.id.tv_group_spent);
            tvNet = itemView.findViewById(R.id.tv_group_net);
            layoutDisputes = itemView.findViewById(R.id.layout_disputes);
            layoutMembers = itemView.findViewById(R.id.layout_members);
            layoutMemberPills = itemView.findViewById(R.id.layout_member_pills);
        }
    }
}
