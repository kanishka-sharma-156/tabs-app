package com.example.tab_able.adapters;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.R;
import com.example.tab_able.data.LedgerItem;

import java.util.List;

public class LedgerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_EXPENSE = 0;
    private static final int TYPE_OFFSET = 1;
    private static final int TYPE_SETTLE = 2;

    private Context context;
    private List<LedgerItem> items;
    private String friendName;

    public LedgerAdapter(Context context, List<LedgerItem> items, String friendName) {
        this.context = context;
        this.items = items;
        this.friendName = friendName != null ? friendName : "Friend";
    }

    public void updateData(List<LedgerItem> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        LedgerItem item = items.get(position);
        if (item.getKind() == LedgerItem.Kind.EXPENSE) return TYPE_EXPENSE;
        if (item.getKind() == LedgerItem.Kind.OFFSET) return TYPE_OFFSET;
        return TYPE_SETTLE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_EXPENSE) {
            View v = LayoutInflater.from(context).inflate(R.layout.item_ledger_expense, parent, false);
            return new ExpenseViewHolder(v);
        } else if (viewType == TYPE_OFFSET) {
            View v = LayoutInflater.from(context).inflate(R.layout.item_ledger_offset, parent, false);
            return new OffsetViewHolder(v);
        } else {
            View v = LayoutInflater.from(context).inflate(R.layout.item_ledger_settle, parent, false);
            return new SettleViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        LedgerItem item = items.get(position);
        if (holder instanceof ExpenseViewHolder) {
            ExpenseViewHolder vh = (ExpenseViewHolder) holder;
            vh.tvTitle.setText(item.getTitle());
            vh.tvTotal.setText("₹" + item.getTotal());

            if ("you".equalsIgnoreCase(item.getPaidBy())) {
                vh.tvDateHeader.setText(item.getDate() + " · you paid");
                vh.tvShareInfo.setText(friendName + " owes you ₹" + (item.getTotal() - item.getYourShare()));
                vh.layoutRoot.setGravity(Gravity.END);
                vh.layoutCardBubble.setBackgroundResource(R.drawable.bg_card_brut_lime);
            } else {
                vh.tvDateHeader.setText(item.getDate() + " · " + friendName + " paid");
                vh.tvShareInfo.setText("your share ₹" + item.getYourShare());
                vh.layoutRoot.setGravity(Gravity.START);
                vh.layoutCardBubble.setBackgroundResource(R.drawable.bg_card_brut_paper);
            }
        } else if (holder instanceof OffsetViewHolder) {
            OffsetViewHolder vh = (OffsetViewHolder) holder;
            vh.tvHeader.setText(item.getDate() + " · AUTO-OFFSET ✂️");
            vh.tvTitles.setText(item.getOffsetTitleA() + " ⇄ " + item.getOffsetTitleB());
            vh.tvDetail.setText("₹" + item.getOffsetCancelled() + " cancelled out — you both paid for each other.");
        } else if (holder instanceof SettleViewHolder) {
            SettleViewHolder vh = (SettleViewHolder) holder;
            vh.tvSettleText.setText("✅ you paid ₹" + item.getSettleAmount() + " · " + item.getDate());
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ExpenseViewHolder extends RecyclerView.ViewHolder {
        LinearLayout layoutRoot, layoutCardBubble;
        TextView tvDateHeader, tvTitle, tvTotal, tvShareInfo;

        public ExpenseViewHolder(@NonNull View itemView) {
            super(itemView);
            layoutRoot = itemView.findViewById(R.id.layout_bubble_root);
            layoutCardBubble = itemView.findViewById(R.id.layout_card_bubble);
            tvDateHeader = itemView.findViewById(R.id.tv_date_header);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvTotal = itemView.findViewById(R.id.tv_total);
            tvShareInfo = itemView.findViewById(R.id.tv_share_info);
        }
    }

    static class OffsetViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader, tvTitles, tvDetail;

        public OffsetViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tv_offset_header);
            tvTitles = itemView.findViewById(R.id.tv_offset_titles);
            tvDetail = itemView.findViewById(R.id.tv_offset_detail);
        }
    }

    static class SettleViewHolder extends RecyclerView.ViewHolder {
        TextView tvSettleText;

        public SettleViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSettleText = itemView.findViewById(R.id.tv_settle_text);
        }
    }
}
