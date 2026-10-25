package com.example.tab_able.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tab_able.LedgerActivity;
import com.example.tab_able.R;
import com.example.tab_able.data.FirebaseUsers;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.TabRepository;
import com.example.tab_able.logic.Money;

import java.util.List;

public class PeopleAdapter extends RecyclerView.Adapter<PeopleAdapter.PersonViewHolder> {

    private Context context;
    private List<Person> people;
    private boolean isOwedTab;

    public PeopleAdapter(Context context, List<Person> people, boolean isOwedTab) {
        this.context = context;
        this.people = people;
        this.isOwedTab = isOwedTab;
    }

    public void updateData(List<Person> newPeople, boolean isOwedTab) {
        this.people = newPeople;
        this.isOwedTab = isOwedTab;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PersonViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_person, parent, false);
        return new PersonViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PersonViewHolder holder, int position) {
        Person person = people.get(position);
        holder.tvAvatar.setText(person.getEmoji());
        holder.tvName.setText(person.getName());
        holder.tvTag.setText(person.getTag());

        if (isOwedTab) {
            holder.tvAmount.setText("+₹" + Math.abs(person.getNetAmount()));
            holder.tvAmount.setTextColor(context.getResources().getColor(R.color.owed_green));
            holder.btnAction.setText("nudge 👉");
            holder.btnAction.setBackgroundResource(R.drawable.bg_pill_cream);
        } else {
            holder.tvAmount.setText("₹" + Math.abs(person.getNetAmount()));
            holder.tvAmount.setTextColor(context.getResources().getColor(R.color.owe_red));
            holder.btnAction.setText("settle");
            holder.btnAction.setBackgroundResource(R.drawable.bg_pill_cream);
        }

        // Click row -> open 1-on-1 Ledger
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, LedgerActivity.class);
            intent.putExtra("person_id", person.getId());
            context.startActivity(intent);
        });

        // Quick action
        holder.btnAction.setOnClickListener(v -> {
            if (isOwedTab) {
                nudge(context, person, holder, position);
            } else {
                settleUp(context, person, holder);
            }
        });
    }

    /**
     * "nudge" sends a real message into the shared space, so the other person
     * actually receives it rather than just seeing a local toast.
     */
    private void nudge(final Context context, final Person person,
                       final PersonViewHolder holder, final int position) {
        person.incrementNudge();
        person.setTag("nudged " + person.getNudgeCount() + "x");
        notifyItemChanged(position);

        TabRepository.getInstance().ensureDirectSpace(person.getId(), new TabRepository.SpaceReadyListener() {
            @Override
            public void onSpaceReady(String spaceId) {
                TabRepository.getInstance().sendMessage(spaceId,
                        "Nudge: bro… the money? 👉👈", "TEXT", 0, "");
                Toast.makeText(context, "Nudged " + person.getName() + " 👉👈 \"bro… the money?\"",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSpaceUnavailable(String reason) {
                Toast.makeText(context, "Could not nudge " + person.getName() + ": " + reason,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * "settle" proposes a payment for the real outstanding balance. The other
     * person has to confirm it before the balance moves, so this is honest about
     * what it does instead of claiming the money was already paid.
     */
    private void settleUp(final Context context, final Person person, final PersonViewHolder holder) {
        // getNetAmount() is rupees; settlements are stored in paise.
        final long owedPaise = Math.abs((long) person.getNetAmount()) * 100L;

        if (owedPaise <= 0) {
            Toast.makeText(context, "You are all square with " + person.getName() + " ✨",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        holder.btnAction.setEnabled(false);
        holder.btnAction.setText("sending…");

        TabRepository.getInstance().ensureDirectSpace(person.getId(), new TabRepository.SpaceReadyListener() {
            @Override
            public void onSpaceReady(String spaceId) {
                TabRepository.getInstance().createSettlement(spaceId, person.getId(), owedPaise,
                        new FirebaseUsers.ActionResult() {
                            @Override
                            public void onSuccess() {
                                holder.btnAction.setText("sent ✓");
                                Toast.makeText(context,
                                        "Proposed " + Money.formatInr(owedPaise) + " to "
                                                + person.getName() + " — waiting for their confirmation",
                                        Toast.LENGTH_LONG).show();
                            }

                            @Override
                            public void onFailure(String message) {
                                holder.btnAction.setEnabled(true);
                                holder.btnAction.setText("settle");
                                Toast.makeText(context,
                                        "Could not settle up: " + message, Toast.LENGTH_LONG).show();
                            }
                        });
            }

            @Override
            public void onSpaceUnavailable(String reason) {
                holder.btnAction.setEnabled(true);
                holder.btnAction.setText("settle");
                Toast.makeText(context, "Could not open this tab: " + reason,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public int getItemCount() {
        return people.size();
    }

    static class PersonViewHolder extends RecyclerView.ViewHolder {
        TextView tvAvatar, tvName, tvTag, tvAmount, btnAction;

        public PersonViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvName = itemView.findViewById(R.id.tv_name);
            tvTag = itemView.findViewById(R.id.tv_tag);
            tvAmount = itemView.findViewById(R.id.tv_amount);
            btnAction = itemView.findViewById(R.id.btn_action);
        }
    }
}
