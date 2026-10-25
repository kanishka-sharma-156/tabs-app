package com.example.tab_able.fragments;

import android.app.DatePickerDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.tab_able.LocationPickerDialog;
import com.example.tab_able.MainActivity;
import com.example.tab_able.R;
import com.example.tab_able.data.Person;
import com.example.tab_able.data.Place;
import com.example.tab_able.data.Space;
import com.example.tab_able.data.TabRepository;
import com.example.tab_able.logic.ItemizedSplit;
import com.example.tab_able.logic.Money;
import com.example.tab_able.logic.SplitCalculator;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AddExpenseFragment extends Fragment {

    private LinearLayout btnScanReceipt, layoutReceiptPreview;
    private TextView tvOcrTitle, tvOcrCaption, btnRemoveReceipt;
    private ImageView ivReceipt;
    private ProgressBar pbOcr;
    private Uri receiptUri = null;
    private ActivityResultLauncher<String> pickReceiptImage;
    private ActivityResultLauncher<Uri> takeReceiptPhoto;

    private EditText etTitle, etAmount;
    private LinearLayout btnDatePicker;
    private TextView tvDate;
    private Spinner spinnerSpace;

    private LinearLayout layoutPaidByPills;
    private TextView btnSplitEqual, btnSplitCustom, btnSplitItems;
    private LinearLayout layoutSplitMembers;
    private TextView tvMathStatus, btnSubmitExpense;

    private String selectedPayerUid = "";

    /** Split mode. ITEMS splits each line item among the people who had it. */
    private enum SplitMode { EQUAL, CUSTOM, ITEMS }

    private SplitMode splitMode = SplitMode.EQUAL;

    /** Line items, live while the form is open. */
    private final List<ItemizedSplit.Item> items = new ArrayList<>();

    private LinearLayout layoutItemsMode, layoutItemsList;
    private TextView btnAddItem, tvItemsSummary;
    /** Guards the amount field so items mode can fill it without a feedback loop. */
    private boolean fillingAmountFromItems = false;

    /**
     * Live views per item row, so a keystroke can refresh the chips and the
     * "each" label WITHOUT rebuilding the row.
     *
     * This matters: the fields are EditTexts, and rebuilding them on every
     * keystroke destroys the one being typed into, so only one letter ever
     * appeared. The list is now rebuilt only when items are added or removed.
     */
    private final List<LinearLayout> itemChipRows = new ArrayList<>();
    private final List<TextView> itemEachLabels = new ArrayList<>();
    private final List<List<TextView>> itemChips = new ArrayList<>();
    private final List<SpaceOption> spaceOptions = new ArrayList<>();
    private SpaceOption selectedSpaceOption = null;

    private final List<String> rosterUids = new ArrayList<>();
    private final Map<String, Boolean> selectedMembers = new HashMap<>();
    private final Map<String, EditText> customInputs = new HashMap<>();
    private final Map<String, TextView> equalShareViews = new HashMap<>();

    private long chosenTimestamp = System.currentTimeMillis();
    private final int dayIndex = 1;

    /** Where this expense happened, or null if the user skipped it. */
    private Place chosenPlace = null;
    private LinearLayout btnPickLocation;
    private TextView tvLocationEmoji, tvLocationName, tvLocationCoords, btnClearLocation;

    public static class SpaceOption {
        public String spaceId;
        public String displayName;
        public List<String> defaultUids;

        public SpaceOption(String spaceId, String displayName, List<String> defaultUids) {
            this.spaceId = spaceId;
            this.displayName = displayName;
            this.defaultUids = defaultUids != null ? defaultUids : new ArrayList<>();
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_add_expense, container, false);

        btnScanReceipt = view.findViewById(R.id.btn_scan_receipt);
        tvOcrTitle = view.findViewById(R.id.tv_ocr_title);
        tvOcrCaption = view.findViewById(R.id.tv_ocr_caption);
        pbOcr = view.findViewById(R.id.pb_ocr);
        layoutReceiptPreview = view.findViewById(R.id.layout_receipt_preview);
        ivReceipt = view.findViewById(R.id.iv_receipt);
        btnRemoveReceipt = view.findViewById(R.id.btn_remove_receipt);

        etTitle = view.findViewById(R.id.et_title);
        etAmount = view.findViewById(R.id.et_amount);
        btnDatePicker = view.findViewById(R.id.btn_date_picker);
        tvDate = view.findViewById(R.id.tv_date);
        spinnerSpace = view.findViewById(R.id.spinner_space);

        layoutPaidByPills = view.findViewById(R.id.layout_paid_by_pills);
        btnSplitEqual = view.findViewById(R.id.btn_split_equal);
        btnSplitCustom = view.findViewById(R.id.btn_split_custom);
        btnSplitItems = view.findViewById(R.id.btn_split_items);
        layoutSplitMembers = view.findViewById(R.id.layout_split_members);
        tvMathStatus = view.findViewById(R.id.tv_math_status);
        btnSubmitExpense = view.findViewById(R.id.btn_submit_expense);

        selectedPayerUid = TabRepository.getInstance().getCurrentUserId();

        setupLocationPicker(view);
        loadSpacesAndFriends();
        setupDatePicker();
        setupReceiptPicker();
        setupSplitToggle();
        setupItemsMode(view);
        etAmount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateMathValidation(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        CheckBox cbIsTransit = view.findViewById(R.id.cb_is_transit);
        LinearLayout layoutTransitFields = view.findViewById(R.id.layout_transit_fields);
        EditText etTransitFrom = view.findViewById(R.id.et_transit_from);
        EditText etTransitTo = view.findViewById(R.id.et_transit_to);

        cbIsTransit.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutTransitFields.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        btnSubmitExpense.setOnClickListener(v -> handleSubmit(cbIsTransit.isChecked(), etTransitFrom, etTransitTo));

        return view;
    }

    private void loadSpacesAndFriends() {
        spaceOptions.clear();
        String myUid = TabRepository.getInstance().getCurrentUserId();

        // 1. Add GROUP spaces
        for (Space sp : TabRepository.getInstance().getSpaces()) {
            if ("GROUP".equalsIgnoreCase(sp.getType())) {
                spaceOptions.add(new SpaceOption(sp.getId(), "🏔️ Group: " + sp.getName(), sp.getMemberUids()));
            }
        }

        // 2. Add DIRECT spaces with friends
        for (Person p : TabRepository.getInstance().getAllPeople()) {
            String friendUid = p.getId();
            String directSpaceId = TabRepository.getDirectSpaceId(myUid, friendUid);
            List<String> uids = new ArrayList<>();
            uids.add(myUid);
            uids.add(friendUid);
            spaceOptions.add(new SpaceOption(directSpaceId, "💬 Direct: " + p.getName(), uids));
        }

        if (spaceOptions.isEmpty()) {
            // Fallback default space if none exist yet
            List<String> uids = new ArrayList<>();
            uids.add(myUid);
            spaceOptions.add(new SpaceOption("default_space", "General Tab", uids));
        }

        ArrayAdapter<SpaceOption> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, spaceOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSpace.setAdapter(adapter);

        spinnerSpace.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedSpaceOption = spaceOptions.get(position);
                onSpaceSelected(selectedSpaceOption);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void onSpaceSelected(SpaceOption option) {
        rosterUids.clear();
        rosterUids.addAll(option.defaultUids);
        if (!rosterUids.contains(TabRepository.getInstance().getCurrentUserId())) {
            rosterUids.add(TabRepository.getInstance().getCurrentUserId());
        }

        selectedMembers.clear();
        for (String uid : rosterUids) {
            selectedMembers.put(uid, true);
        }

        setupPaidByPills();
        onRosterChanged();
    }

    private void setupDatePicker() {
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
        tvDate.setText(sdf.format(new Date(chosenTimestamp)) + " 📅");

        btnDatePicker.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(chosenTimestamp);
            new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
                Calendar c = Calendar.getInstance();
                c.set(year, month, dayOfMonth);
                chosenTimestamp = c.getTimeInMillis();
                tvDate.setText(sdf.format(new Date(chosenTimestamp)) + " 📅");
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
    }

    /**
     * Receipt capture: camera or gallery.
     *
     * Tapping the card offers both. Taking a photo writes into a private cache
     * folder exposed through FileProvider, so no storage permission is needed.
     */
    private void setupReceiptPicker() {
        pickReceiptImage = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    receiptUri = uri;
                    showReceiptPreview();
                    simulateReadingReceipt();
                });

        takeReceiptPhoto = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success == null || !success) {
                        // User backed out of the camera.
                        if (receiptUri == null) clearReceipt();
                        return;
                    }
                    showReceiptPreview();
                    simulateReadingReceipt();
                });

        btnScanReceipt.setOnClickListener(v -> showReceiptSourceDialog());
        btnRemoveReceipt.setOnClickListener(v -> clearReceipt());
    }

    /** Asks whether to use the camera or pick an existing image. */
    private void showReceiptSourceDialog() {
        if (getContext() == null) return;

        new AlertDialog.Builder(getContext())
                .setTitle("Add a receipt")
                .setItems(new String[]{"Take a photo", "Choose from gallery"}, (dialog, which) -> {
                    if (which == 0) {
                        launchCamera();
                    } else {
                        try {
                            pickReceiptImage.launch("image/*");
                        } catch (Exception e) {
                            Toast.makeText(getContext(),
                                    "No gallery app available", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", (dialog, id) -> dialog.dismiss())
                .show();
    }

    /**
     * Opens the camera app to photograph the receipt.
     *
     * Uses ACTION_IMAGE_CAPTURE via ActivityResultContracts.TakePicture, so the
     * camera app takes the photo and holds the camera permission. This app declares
     * no CAMERA permission deliberately: declaring it makes Android require a
     * runtime grant, and without that grant the launch fails outright with
     * "Permission Denial ... with revoked permission android.permission.CAMERA".
     */
    private void launchCamera() {
        if (getContext() == null) return;

        // Check before doing any work, so the failure is a clear message rather
        // than an exception from deep inside the contract.
        android.content.Intent probe = new android.content.Intent(
                android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        if (probe.resolveActivity(requireContext().getPackageManager()) == null) {
            Toast.makeText(getContext(),
                    "No camera app found on this device - use \"Choose from gallery\"",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            java.io.File dir = new java.io.File(requireContext().getCacheDir(), "receipts");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new java.io.IOException("could not create the receipt folder");
            }
            java.io.File photo = new java.io.File(dir, "receipt_" + System.currentTimeMillis() + ".jpg");

            receiptUri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    photo);

            takeReceiptPhoto.launch(receiptUri);
        } catch (Exception e) {
            receiptUri = null;
            Toast.makeText(getContext(),
                    "Could not open the camera: " + e.getMessage()
                            + "\nTry \"Choose from gallery\" instead.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The "reading the receipt" step.
     *
     * IMPORTANT: this does not actually read anything. There is no OCR in this app.
     * It runs a short progress animation and then fills in a sample item list so the
     * flow can be tried end to end. Every value it writes is editable, and the UI
     * says plainly that the items are a sample - the alternative would be a scanner
     * that looks clever and silently fills in the wrong numbers.
     */
    private void simulateReadingReceipt() {
        if (!isAdded() || getView() == null) return;

        pbOcr.setVisibility(View.VISIBLE);
        tvOcrTitle.setText("reading receipt...");
        tvOcrCaption.setText("looking for items and prices");

        final String[] steps = {
                "reading receipt...",
                "finding items...",
                "adding up prices...",
                "almost there..."
        };

        for (int i = 0; i < steps.length; i++) {
            final String label = steps[i];
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded() || getView() == null) return;
                tvOcrTitle.setText(label);
            }, 320L * i);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded() || getView() == null) return;
            pbOcr.setVisibility(View.GONE);
            fillSampleItems();
        }, 320L * steps.length + 240L);
    }

    /**
     * Fills the items list with a sample restaurant bill and switches to items mode
     * so the shares are visible immediately.
     *
     * Everyone in the roster starts selected for the shared dishes, which is the
     * common case; untick anyone who did not have a given item.
     */
    private void fillSampleItems() {
        if (rosterUids.isEmpty()) {
            // No space chosen yet, so there is nobody to assign items to. Remember
            // that a fill is owed and do it as soon as the roster is known.
            pendingSampleFill = true;
            Toast.makeText(getContext(),
                    "Pick a friend or group above, then tap add item to fill the list",
                    Toast.LENGTH_LONG).show();
            return;
        }
        pendingSampleFill = false;

        List<String> all = new ArrayList<>(rosterUids);
        List<String> firstTwo = new ArrayList<>(all.subList(0, Math.min(2, all.size())));
        List<String> firstOne = new ArrayList<>(all.subList(0, 1));

        items.clear();
        items.add(new ItemizedSplit.Item("Paneer Tikka", 38000, new ArrayList<>(all)));
        items.add(new ItemizedSplit.Item("Butter Naan", 12000, new ArrayList<>(all)));
        items.add(new ItemizedSplit.Item("Dal Makhani", 26000, new ArrayList<>(all)));
        items.add(new ItemizedSplit.Item("Cold Coffee", 18000, firstTwo));
        items.add(new ItemizedSplit.Item("Gulab Jamun", 14000, firstOne));

        // Switching to items mode reveals the cards; set the mode before rendering.
        if (splitMode != SplitMode.ITEMS) {
            setSplitMode(SplitMode.ITEMS);
        } else {
            renderItems();
        }

        if (etTitle.getText().toString().trim().isEmpty()) {
            etTitle.setText("Restaurant bill");
        }

        tvOcrTitle.setText("receipt attached - items filled in");
        tvOcrCaption.setText("sample items: edit them to match your receipt");

        // Move the list into view so the filled items are obvious.
        if (layoutItemsMode != null) {
            layoutItemsMode.post(() -> layoutItemsMode.requestFocus());
        }

        updateMathValidation();

        Toast.makeText(getContext(),
                "Items filled in as a sample - please check them against your receipt",
                Toast.LENGTH_LONG).show();
    }

    private void showReceiptPreview() {
        if (receiptUri == null || !isAdded()) return;

        try {
            ivReceipt.setImageURI(receiptUri);
        } catch (Exception e) {
            // Some content providers refuse a direct URI load; say so instead of
            // showing a blank box.
            ivReceipt.setImageDrawable(null);
            Toast.makeText(getContext(), "Could not display that image", Toast.LENGTH_SHORT).show();
        }

        layoutReceiptPreview.setVisibility(View.VISIBLE);
        tvOcrTitle.setText("receipt attached");
        tvOcrCaption.setText("type the items below - tap to change the photo");
        pbOcr.setVisibility(View.GONE);
    }

    private void clearReceipt() {
        receiptUri = null;
        if (ivReceipt != null) ivReceipt.setImageDrawable(null);
        if (layoutReceiptPreview != null) layoutReceiptPreview.setVisibility(View.GONE);
        if (tvOcrTitle != null) tvOcrTitle.setText("add a receipt photo");
        if (tvOcrCaption != null) {
            tvOcrCaption.setText("pick an image, then enter the items yourself");
        }
    }

    private void setupPaidByPills() {
        layoutPaidByPills.removeAllViews();
        String myUid = TabRepository.getInstance().getCurrentUserId();
        if (selectedPayerUid.isEmpty()) selectedPayerUid = myUid;

        for (String uid : rosterUids) {
            TextView pill = new TextView(getContext());
            boolean isSelected = uid.equalsIgnoreCase(selectedPayerUid);
            TabRepository.getInstance().resolveUserName(uid, name -> {
                pill.setText((isSelected ? "• " : "○ ") + (uid.equals(myUid) ? "You" : name));
            });
            pill.setTextSize(13f);
            pill.setPadding(28, 10, 28, 10);
            pill.setBackgroundResource(isSelected ? R.drawable.bg_pill_black : R.drawable.bg_pill_outlined);
            pill.setTextColor(getResources().getColor(isSelected ? R.color.white : R.color.ink));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 12, 0);
            pill.setLayoutParams(params);

            pill.setOnClickListener(v -> {
                selectedPayerUid = uid;
                setupPaidByPills();
            });

            layoutPaidByPills.addView(pill);
        }
    }

    private void setupSplitToggle() {
        btnSplitEqual.setOnClickListener(v -> setSplitMode(SplitMode.EQUAL));
        btnSplitCustom.setOnClickListener(v -> setSplitMode(SplitMode.CUSTOM));
        btnSplitItems.setOnClickListener(v -> setSplitMode(SplitMode.ITEMS));
    }

    private void setSplitMode(SplitMode mode) {
        splitMode = mode;

        btnSplitEqual.setBackgroundResource(
                mode == SplitMode.EQUAL ? R.drawable.bg_pill_pink : android.R.color.transparent);
        btnSplitCustom.setBackgroundResource(
                mode == SplitMode.CUSTOM ? R.drawable.bg_pill_pink : android.R.color.transparent);
        btnSplitItems.setBackgroundResource(
                mode == SplitMode.ITEMS ? R.drawable.bg_pill_pink : android.R.color.transparent);

        boolean itemsMode = mode == SplitMode.ITEMS;

        // In items mode the per-person list is replaced by the item cards, because
        // who pays is decided item by item rather than per person.
        layoutSplitMembers.setVisibility(itemsMode ? View.GONE : View.VISIBLE);
        if (layoutItemsMode != null) {
            layoutItemsMode.setVisibility(itemsMode ? View.VISIBLE : View.GONE);
        }

        if (itemsMode) {
            if (items.isEmpty()) addItem("", 0, new ArrayList<>(rosterUids));
            renderItems();
        } else {
            setupSplitMembersList();
        }

        updateMathValidation();
    }

    private void setupSplitMembersList() {
        layoutSplitMembers.removeAllViews();
        customInputs.clear();
        equalShareViews.clear();
        String myUid = TabRepository.getInstance().getCurrentUserId();

        for (String uid : rosterUids) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(16, 12, 16, 12);
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox cb = new CheckBox(getContext());
            cb.setChecked(Boolean.TRUE.equals(selectedMembers.get(uid)));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                selectedMembers.put(uid, isChecked);
                updateMathValidation();
            });

            TextView name = new TextView(getContext());
            name.setText(uid.equals(myUid) ? "You" : uid);
            name.setTextSize(16f);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(getResources().getColor(R.color.ink));
            TabRepository.getInstance().resolveUserName(uid, resolved -> {
                name.setText(uid.equals(myUid) ? "You" : resolved);
            });

            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameParams.setMargins(16, 0, 0, 0);

            row.addView(cb);
            row.addView(name, nameParams);

            if (splitMode == SplitMode.EQUAL) {
                TextView tvShare = new TextView(getContext());
                tvShare.setText("₹0");
                tvShare.setTextSize(14f);
                tvShare.setTextColor(getResources().getColor(R.color.text_secondary));
                equalShareViews.put(uid, tvShare);
                row.addView(tvShare);
            } else {
                EditText etCustom = new EditText(getContext());
                etCustom.setHint("₹0");
                etCustom.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                etCustom.setBackgroundResource(R.drawable.bg_pill_outlined);
                etCustom.setPadding(24, 8, 24, 8);
                etCustom.setTextSize(14f);
                etCustom.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateMathValidation(); }
                    @Override public void afterTextChanged(Editable s) {}
                });
                customInputs.put(uid, etCustom);
                row.addView(etCustom);
            }

            layoutSplitMembers.addView(row);
        }
    }

    /**
     * Called whenever the selected space changes, because the roster changes with
     * it: in items mode the per-item people chips must be rebuilt from the new
     * member list.
     */
    private void onRosterChanged() {
        if (splitMode == SplitMode.ITEMS) {
            // Drop consumers who are no longer part of this space, otherwise an item
            // could be charged to somebody who is not in the group.
            for (ItemizedSplit.Item item : items) {
                item.getConsumerUids().retainAll(rosterUids);
                if (item.getConsumerUids().isEmpty() && !rosterUids.isEmpty()) {
                    item.getConsumerUids().addAll(rosterUids);
                }
            }
            renderItems();
        } else {
            setupSplitMembersList();
        }
        updateMathValidation();

        // A receipt was scanned before a space was chosen; now there are people to
        // assign the items to.
        if (pendingSampleFill && !rosterUids.isEmpty()) {
            fillSampleItems();
        }
    }

    // ------------------------------------------------------------ items mode

    private void setupItemsMode(View root) {
        layoutItemsMode = root.findViewById(R.id.layout_items_mode);
        layoutItemsList = root.findViewById(R.id.layout_items_list);
        btnAddItem = root.findViewById(R.id.btn_add_item);
        tvItemsSummary = root.findViewById(R.id.tv_items_summary);

        btnAddItem.setOnClickListener(v -> addItem("", 0, new ArrayList<>(rosterUids)));
    }

    /** Set when a receipt was "read" before a space was chosen, so the fill can wait. */
    private boolean pendingSampleFill = false;

    private void addItem(String name, long amountPaise, List<String> consumers) {
        items.add(new ItemizedSplit.Item(name, amountPaise, consumers));
        renderItems();
    }

    /**
     * Redraws every item card.
     *
     * Rebuilt wholesale on each change, which keeps the chips and the summary
     * consistent with the list without any incremental-update bugs.
     */
    private void renderItems() {
        if (layoutItemsList == null) return;
        layoutItemsList.removeAllViews();
        itemChipRows.clear();
        itemChips.clear();
        itemEachLabels.clear();
        String myUid = TabRepository.getInstance().getCurrentUserId();

        if (items.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("no items yet - tap \"+ add item\"");
            empty.setTextSize(13f);
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setPadding(16, 16, 16, 16);
            layoutItemsList.addView(empty);
        }

        for (int index = 0; index < items.size(); index++) {
            final int position = index;
            final ItemizedSplit.Item item = items.get(index);

            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card_brut_paper);
            card.setPadding(16, 14, 16, 14);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, 10);
            card.setLayoutParams(cardParams);

            // Row 1: item name + delete
            LinearLayout topRow = new LinearLayout(getContext());
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            EditText etName = new EditText(getContext());
            etName.setHint("item name, e.g. Large pizza");
            etName.setText(item.getName());
            etName.setTextSize(15f);
            etName.setTypeface(null, Typeface.BOLD);
            etName.setBackgroundResource(R.drawable.bg_pill_outlined);
            etName.setPadding(20, 10, 20, 10);
            etName.setMaxLines(1);
            etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            etName.addTextChangedListener(new SimpleWatcher(() -> {
                item.setName(etName.getText().toString());
                updateMathValidation();
            }));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            topRow.addView(etName, nameParams);

            TextView btnRemove = new TextView(getContext());
            btnRemove.setText("✕");
            btnRemove.setTextSize(16f);
            btnRemove.setTypeface(null, Typeface.BOLD);
            btnRemove.setPadding(20, 8, 8, 8);
            btnRemove.setOnClickListener(v -> {
                if (position < items.size()) {
                    items.remove(position);
                    renderItems();
                }
            });
            topRow.addView(btnRemove);
            card.addView(topRow);

            // Row 2: price + "each" hint
            LinearLayout priceRow = new LinearLayout(getContext());
            priceRow.setOrientation(LinearLayout.HORIZONTAL);
            priceRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams priceRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            priceRowParams.setMargins(0, 10, 0, 0);
            priceRow.setLayoutParams(priceRowParams);

            EditText etPrice = new EditText(getContext());
            etPrice.setHint("₹0");
            etPrice.setTextSize(15f);
            etPrice.setTypeface(null, Typeface.BOLD);
            etPrice.setBackgroundResource(R.drawable.bg_pill_outlined);
            etPrice.setPadding(20, 10, 20, 10);
            etPrice.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            if (item.getAmountPaise() > 0) {
                etPrice.setText(String.valueOf(item.getAmountPaise() / 100));
            }
            etPrice.addTextChangedListener(new SimpleWatcher(() -> {
                long paise = 0;
                try {
                    paise = Money.parsePaise(etPrice.getText().toString());
                } catch (Exception ignored) {
                    paise = 0;
                }
                item.setAmountPaise(paise);
                updateMathValidation();
            }));
            priceRow.addView(etPrice);

            final TextView tvEach = new TextView(getContext());
            tvEach.setTextSize(12f);
            tvEach.setTextColor(getResources().getColor(R.color.text_secondary));
            tvEach.setPadding(16, 0, 0, 0);
            priceRow.addView(tvEach);
            card.addView(priceRow);

            // Row 3: who consumed it. Tapping a chip toggles that person.
            LinearLayout chips = new LinearLayout(getContext());
            chips.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams chipRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipRowParams.setMargins(0, 10, 0, 0);
            chips.setLayoutParams(chipRowParams);

            List<TextView> chipRefs = new ArrayList<>();
            for (String uid : rosterUids) {
                TextView chip = new TextView(getContext());
                chip.setTextSize(12f);
                chip.setTypeface(null, Typeface.BOLD);
                chip.setPadding(24, 10, 24, 10);
                chip.setTextColor(getResources().getColor(R.color.ink));

                final TextView chipRef = chip;
                TabRepository.getInstance().resolveUserName(uid, resolved ->
                        chipRef.setText(uid.equals(myUid) ? "You" : resolved));
                chip.setText(uid.equals(myUid) ? "You" : uid);

                // Toggling only recolours the chips and refreshes the labels - it does
                // NOT rebuild the row, so an in-progress edit is never disturbed.
                chip.setOnClickListener(v -> {
                    if (item.getConsumerUids().contains(uid)) {
                        item.getConsumerUids().remove(uid);
                    } else {
                        item.getConsumerUids().add(uid);
                    }
                    refreshItemDynamics();
                    updateMathValidation();
                });

                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                chipParams.setMargins(0, 0, 8, 0);
                chip.setLayoutParams(chipParams);
                chips.addView(chip);
                chipRefs.add(chip);
            }
            card.addView(chips);

            // Remember the live views for this row so they can be updated in place.
            itemChipRows.add(chips);
            itemChips.add(chipRefs);
            itemEachLabels.add(tvEach);

            layoutItemsList.addView(card);
        }

        // Paint the chips and each-labels for the freshly built rows.
        refreshItemDynamics();
    }

    /**
     * Refreshes the parts of the item cards that change as you type: chip colours
     * and the "₹x each · n people" labels, plus the per-person summary.
     *
     * Deliberately does NOT touch the EditTexts. Rebuilding them on every keystroke
     * is what stopped the user from typing more than one letter.
     */
    private void refreshItemDynamics() {
        String myUid = TabRepository.getInstance().getCurrentUserId();

        for (int i = 0; i < items.size(); i++) {
            if (i >= itemChips.size() || i >= itemEachLabels.size()) break;

            ItemizedSplit.Item item = items.get(i);

            // Chip colours reflect who is currently selected.
            List<String> consumers = item.getConsumerUids();
            for (int c = 0; c < itemChips.get(i).size() && c < rosterUids.size(); c++) {
                String uid = rosterUids.get(c);
                itemChips.get(i).get(c).setBackgroundResource(
                        consumers.contains(uid) ? R.drawable.bg_pill_lime : R.drawable.bg_pill_outlined);
            }

            TextView tvEach = itemEachLabels.get(i);
            if (item.getAmountPaise() > 0 && !consumers.isEmpty()) {
                int ppl = consumers.size();
                long each = item.getAmountPaise() / ppl;
                tvEach.setText(Money.formatInr(each) + " each · " + ppl
                        + (ppl == 1 ? " person" : " people"));
            } else if (item.getAmountPaise() > 0) {
                tvEach.setText("nobody selected yet");
            } else {
                tvEach.setText("enter a price");
            }
        }
    }

    /** Small adapter so each field needs only its "on change" behaviour. */
    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            onChange.run();
        }
        @Override public void afterTextChanged(Editable s) {}
    }

    private List<String> getSelectedUids() {
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : selectedMembers.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    private void updateMathValidation() {
        if (etAmount == null || tvMathStatus == null || fillingAmountFromItems) return;

        if (splitMode == SplitMode.ITEMS) {
            updateItemsValidation();
            return;
        }

        long totalPaise = 0;
        try {
            totalPaise = Money.parsePaise(etAmount.getText().toString());
        } catch (Exception ignored) {}

        List<String> activeUids = getSelectedUids();

        if (splitMode == SplitMode.EQUAL) {
            Map<String, Long> shares = SplitCalculator.splitEqually(totalPaise, activeUids);
            for (Map.Entry<String, TextView> entry : equalShareViews.entrySet()) {
                Long share = shares.get(entry.getKey());
                entry.getValue().setText(share != null ? Money.formatInr(share) : "₹0");
            }
            if (activeUids.isEmpty()) {
                tvMathStatus.setText("pick at least one person to split with");
                tvMathStatus.setTextColor(getResources().getColor(R.color.owe_red));
                btnSubmitExpense.setEnabled(false);
                return;
            }
            tvMathStatus.setText(Money.formatInr(totalPaise) + " split "
                    + activeUids.size() + (activeUids.size() == 1 ? " way" : " ways"));
            tvMathStatus.setTextColor(getResources().getColor(R.color.owed_green));
            btnSubmitExpense.setEnabled(true);
            return;
        }

        Map<String, Long> customAmounts = new HashMap<>();
        for (Map.Entry<String, EditText> entry : customInputs.entrySet()) {
            if (Boolean.TRUE.equals(selectedMembers.get(entry.getKey()))) {
                try {
                    customAmounts.put(entry.getKey(),
                            Money.parsePaise(entry.getValue().getText().toString()));
                } catch (Exception ignored) {
                    customAmounts.put(entry.getKey(), 0L);
                }
            }
        }
        SplitCalculator.SplitResult result = SplitCalculator.splitCustom(totalPaise, customAmounts);
        if (result.isSuccess()) {
            tvMathStatus.setText(Money.formatInr(totalPaise) + " assigned ✓ perfect");
            tvMathStatus.setTextColor(getResources().getColor(R.color.owed_green));
            btnSubmitExpense.setEnabled(true);
        } else {
            long diff = result.getDifference();
            String diffStr = Money.formatInr(Math.abs(diff));
            String msg = diff > 0 ? ("Over by " + diffStr) : ("Short by " + diffStr);
            tvMathStatus.setText(msg + " - amounts must add up exactly");
            tvMathStatus.setTextColor(getResources().getColor(R.color.owe_red));
            btnSubmitExpense.setEnabled(false);
        }
    }

    /**
     * Items mode validation.
     *
     * The amount field is driven BY the items: the bill total is the sum of the line
     * items. Items that carry a price but have nobody selected block submit, because
     * there is no honest way to charge them to anyone.
     */
    private void updateItemsValidation() {
        ItemizedSplit.Result result = ItemizedSplit.compute(items, rosterUids);

        // Keep the amount field showing the running total, but never fight the user
        // for it: setText() moves the caret, so rewriting the field on every
        // keystroke is what made it impossible to type in. Skip while it is focused
        // or already showing the right number.
        if (result.getTotalPaise() > 0 && !etAmount.hasFocus()) {
            String total = String.valueOf(result.getTotalPaise() / 100);
            if (!total.equals(etAmount.getText().toString())) {
                fillingAmountFromItems = true;
                etAmount.setText(total);
                fillingAmountFromItems = false;
            }
        }

        // Update the chips and labels in place. Do NOT rebuild the rows here: this
        // method runs on every keystroke, and rebuilding would destroy the field
        // being typed into.
        refreshItemDynamics();

        if (result.getUsableItems() == 0) {
            tvMathStatus.setText("add an item with a price and pick who had it");
            tvMathStatus.setTextColor(getResources().getColor(R.color.owe_red));
            btnSubmitExpense.setEnabled(false);
            if (tvItemsSummary != null) tvItemsSummary.setText("each person's total shows here");
            return;
        }

        if (result.hasUnassignedItems()) {
            tvMathStatus.setText(Money.formatInr(result.getUnassignedPaise())
                    + " of items have nobody selected - tap who had them");
            tvMathStatus.setTextColor(getResources().getColor(R.color.owe_red));
            btnSubmitExpense.setEnabled(false);
            if (tvItemsSummary != null) tvItemsSummary.setText("each person's total shows here");
            return;
        }

        tvMathStatus.setText(Money.formatInr(result.getTotalPaise()) + " across "
                + result.getUsableItems() + (result.getUsableItems() == 1 ? " item" : " items")
                + " ✓ split by who had what");
        tvMathStatus.setTextColor(getResources().getColor(R.color.owed_green));
        btnSubmitExpense.setEnabled(true);

        if (tvItemsSummary != null) {
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, Long> entry : result.getSharesPerUid().entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (summary.length() > 0) summary.append("   ·   ");
                summary.append(TabRepository.getInstance().nameFor(entry.getKey()))
                        .append(' ').append(Money.formatInr(entry.getValue()));
            }
            tvItemsSummary.setText(summary.length() == 0
                    ? "each person's total shows here" : summary.toString());
        }
    }

    /**
     * Wires the optional location field.
     *
     * The chosen place is written onto the expense document as a nested "place"
     * map, which is exactly what SpaceExpense.place reads back and what the map tab
     * looks for, so a pin appears there automatically.
     */
    private void setupLocationPicker(View root) {
        btnPickLocation = root.findViewById(R.id.btn_pick_location);
        tvLocationEmoji = root.findViewById(R.id.tv_location_emoji);
        tvLocationName = root.findViewById(R.id.tv_location_name);
        tvLocationCoords = root.findViewById(R.id.tv_location_coords);
        btnClearLocation = root.findViewById(R.id.btn_clear_location_field);

        renderChosenPlace();

        btnPickLocation.setOnClickListener(v -> {
            LocationPickerDialog dialog = LocationPickerDialog.newInstance(chosenPlace);
            dialog.setListener(place -> {
                chosenPlace = place;
                renderChosenPlace();
            });
            dialog.show(getChildFragmentManager(), "location_picker");
        });

        btnClearLocation.setOnClickListener(v -> {
            chosenPlace = null;
            renderChosenPlace();
        });
    }

    private void renderChosenPlace() {
        if (tvLocationName == null) return;

        if (chosenPlace == null) {
            tvLocationEmoji.setText("📍");
            tvLocationName.setText("tap to pick on the map");
            tvLocationCoords.setText("optional - shows up as a pin on the map tab");
            btnClearLocation.setVisibility(View.GONE);
            return;
        }

        tvLocationEmoji.setText("📍");
        tvLocationName.setText(chosenPlace.getName());
        tvLocationCoords.setText(String.format(Locale.US, "%.4f, %.4f",
                chosenPlace.getLat(), chosenPlace.getLng()));
        btnClearLocation.setVisibility(View.VISIBLE);
    }

    private void handleSubmit(boolean isTransit, EditText etTransitFrom, EditText etTransitTo) {
        if (selectedSpaceOption == null) {
            Toast.makeText(getContext(), "Please select a space or friend", Toast.LENGTH_SHORT).show();
            return;
        }

        String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) title = "Expense";

        long amountPaise;
        try {
            amountPaise = Money.parsePaise(etAmount.getText().toString());
        } catch (Exception e) {
            Toast.makeText(getContext(), e.getMessage() != null
                    ? e.getMessage() : "Enter a valid amount, e.g. 250 or 99.50",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (amountPaise <= 0) {
            Toast.makeText(getContext(), "Please enter an amount > ₹0", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> activeUids = getSelectedUids();
        // In items mode the participants come from the items themselves, so this
        // check only applies to the equal / custom modes.
        if (splitMode != SplitMode.ITEMS && activeUids.isEmpty()) {
            Toast.makeText(getContext(), "Pick at least one person to split with",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Work out who owes what, according to the chosen split mode.
        Map<String, Long> finalShares;
        ItemizedSplit.Result itemResult = null;

        if (splitMode == SplitMode.ITEMS) {
            itemResult = ItemizedSplit.compute(items, rosterUids);
            if (itemResult.getUsableItems() == 0) {
                Toast.makeText(getContext(), "Add an item with a price and pick who had it",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (itemResult.hasUnassignedItems()) {
                Toast.makeText(getContext(),
                        Money.formatInr(itemResult.getUnassignedPaise())
                                + " of items have nobody selected - tap who had them",
                        Toast.LENGTH_LONG).show();
                return;
            }
            // The bill total comes from the items, not from the amount field.
            amountPaise = itemResult.getTotalPaise();
            finalShares = itemResult.getSharesPerUid();
        } else if (splitMode == SplitMode.EQUAL) {
            finalShares = SplitCalculator.splitEqually(amountPaise, activeUids);
        } else {
            Map<String, Long> customAmounts = new HashMap<>();
            for (Map.Entry<String, EditText> entry : customInputs.entrySet()) {
                if (Boolean.TRUE.equals(selectedMembers.get(entry.getKey()))) {
                    try {
                        customAmounts.put(entry.getKey(), Money.parsePaise(entry.getValue().getText().toString()));
                    } catch (Exception ignored) {
                        customAmounts.put(entry.getKey(), 0L);
                    }
                }
            }
            SplitCalculator.SplitResult res = SplitCalculator.splitCustom(amountPaise, customAmounts);
            if (!res.isSuccess()) {
                Toast.makeText(getContext(), "Custom splits must add up to the total exactly",
                        Toast.LENGTH_LONG).show();
                return;
            }
            finalShares = customAmounts;
        }

        // Drop anyone who owes nothing so the stored split is exactly the people
        // involved. An empty share set would also fail the balance maths.
        Map<String, Long> payableShares = new HashMap<>();
        for (Map.Entry<String, Long> entry : finalShares.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                payableShares.put(entry.getKey(), entry.getValue());
            }
        }
        if (payableShares.isEmpty()) {
            Toast.makeText(getContext(), "Nobody has a share of this expense yet",
                    Toast.LENGTH_LONG).show();
            return;
        }
        finalShares = payableShares;

        final String finalTitle = title;
        // Copy for the commit callbacks below. The shares go into the in-memory map
        // synchronously, so only the amount needs to be final.
        final long finalAmountPaise = amountPaise;
        String spaceId = selectedSpaceOption.spaceId;
        String myUid = TabRepository.getInstance().getCurrentUserId();
        String myName = TabRepository.getInstance().getCurrentUserName();

        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
        WriteBatch batch = firestore.batch();

        String expenseId = firestore.collection("spaces").document(spaceId).collection("expenses").document().getId();
        String messageId = firestore.collection("spaces").document(spaceId).collection("messages").document().getId();

        Map<String, Object> expenseMap = new HashMap<>();
        expenseMap.put("id", expenseId);
        expenseMap.put("title", finalTitle);
        expenseMap.put("amountPaise", amountPaise);
        // Field name must be paidByUid: that is what SpaceExpense reads and what
        // the Firestore rules validate on create. Writing "payerUid" here silently
        // failed the security rules, so no expense ever saved.
        expenseMap.put("paidByUid", selectedPayerUid);
        expenseMap.put("shares", finalShares);
        expenseMap.put("occurredAt", chosenTimestamp);
        expenseMap.put("dayIndex", dayIndex);
        expenseMap.put("createdBy", myUid);
        expenseMap.put("status", "ACTIVE");

        // Keep the itemisation on the document so the breakdown is visible to
        // everyone in the space, not just whoever entered it.
        if (itemResult != null) {
            List<Map<String, Object>> itemMaps = new ArrayList<>();
            for (ItemizedSplit.Item item : items) {
                if (item.getAmountPaise() <= 0 || item.getConsumerUids().isEmpty()) continue;
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("name", item.getName());
                itemMap.put("amountPaise", item.getAmountPaise());
                itemMap.put("consumerUids", new ArrayList<>(item.getConsumerUids()));
                itemMaps.add(itemMap);
            }
            expenseMap.put("items", itemMaps);
            expenseMap.put("splitMode", "ITEMS");
        } else {
            expenseMap.put("splitMode", splitMode == SplitMode.EQUAL ? "EQUAL" : "CUSTOM");
        }

        // Where it happened. Stored as a nested map so SpaceExpense.place picks it up
        // and the map tab can group expenses by location.
        if (chosenPlace != null) {
            Map<String, Object> placeMap = new HashMap<>();
            placeMap.put("placeId", chosenPlace.getPlaceId());
            placeMap.put("name", chosenPlace.getName());
            placeMap.put("lat", chosenPlace.getLat());
            placeMap.put("lng", chosenPlace.getLng());
            expenseMap.put("place", placeMap);
        }

        if (isTransit) {
            expenseMap.put("transitFrom", etTransitFrom.getText().toString().trim());
            expenseMap.put("transitTo", etTransitTo.getText().toString().trim());
        }

        batch.set(firestore.collection("spaces").document(spaceId).collection("expenses").document(expenseId), expenseMap);

        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put("id", messageId);
        messageMap.put("senderUid", myUid);
        messageMap.put("senderName", myName);
        messageMap.put("text", "Added expense: " + finalTitle + " (" + Money.formatInr(finalAmountPaise) + ")");
        messageMap.put("type", "EXPENSE_SHARE");
        messageMap.put("amount", (int)(finalAmountPaise / 100));
        messageMap.put("timestamp", chosenTimestamp);
        messageMap.put("status", "SENT");

        batch.set(firestore.collection("spaces").document(spaceId).collection("messages").document(messageId), messageMap);

        // Update space totals using FieldValue.increment
        Map<String, Object> spaceUpdate = new HashMap<>();
        spaceUpdate.put("totalSpent", FieldValue.increment(finalAmountPaise));
        batch.set(firestore.collection("spaces").document(spaceId), spaceUpdate, SetOptions.merge());

        batch.commit()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(getContext(), "Added \"" + finalTitle + "\" · " + Money.formatInr(finalAmountPaise) + " 🎉", Toast.LENGTH_SHORT).show();
                    // Reset the form so a second expense does not silently reuse the
                    // previous location or line items.
                    chosenPlace = null;
                    renderChosenPlace();
                    items.clear();
                    renderItems();
                    clearReceipt();
                    etTitle.setText("");
                    etAmount.setText("");
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).selectTab(0);
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(getContext(), "Failed to save expense: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}
