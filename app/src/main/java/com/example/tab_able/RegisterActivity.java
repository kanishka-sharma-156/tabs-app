package com.example.tab_able;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tab_able.data.FirebaseUsers;
import com.example.tab_able.data.TabRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

/**
 * Three-step signup: email, verification code, then profile details.
 *
 * The account is only created in step 3, and the username is checked AFTER the
 * Firebase Auth account exists. That ordering is deliberate: the usernames
 * collection denies reads to unauthenticated clients, so a pre-signup check
 * would always come back empty and let two people claim the same username.
 */
public class RegisterActivity extends AppCompatActivity {

    private static final String DEMO_CODE = "1234";

    private View progressStep1, progressStep2, progressStep3;
    private LinearLayout step1Layout, step2Layout, step3Layout;

    private EditText etEmail, etOtp;
    private TextView btnSendOtp, btnVerifyOtp, btnResendCode, btnStep1Login;
    private TextView tvOtpSentTo;

    private EditText etFullName, etRegUsername, etRegPassword, etRegConfirmPassword;
    private TextView btnCreateAccount;
    private View progressRegister;

    private String verifiedEmail = "";
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        progressStep1 = findViewById(R.id.progress_step_1);
        progressStep2 = findViewById(R.id.progress_step_2);
        progressStep3 = findViewById(R.id.progress_step_3);

        step1Layout = findViewById(R.id.step_1_layout);
        step2Layout = findViewById(R.id.step_2_layout);
        step3Layout = findViewById(R.id.step_3_layout);

        etEmail = findViewById(R.id.et_email);
        btnSendOtp = findViewById(R.id.btn_send_otp);
        btnStep1Login = findViewById(R.id.btn_step1_login);

        tvOtpSentTo = findViewById(R.id.tv_otp_sent_to);
        etOtp = findViewById(R.id.et_otp);
        btnVerifyOtp = findViewById(R.id.btn_verify_otp);
        btnResendCode = findViewById(R.id.btn_resend_code);

        etFullName = findViewById(R.id.et_full_name);
        etRegUsername = findViewById(R.id.et_reg_username);
        etRegPassword = findViewById(R.id.et_reg_password);
        etRegConfirmPassword = findViewById(R.id.et_reg_confirm_password);
        btnCreateAccount = findViewById(R.id.btn_create_account);
        progressRegister = findViewById(R.id.progress_register);

        btnStep1Login.setOnClickListener(v -> {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        btnSendOtp.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim().toLowerCase();
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast("Please enter a valid email address");
                return;
            }
            verifiedEmail = email;
            tvOtpSentTo.setText("sent to " + verifiedEmail + " · change");
            toast("Verification code sent to " + verifiedEmail + " 📩");

            step1Layout.setVisibility(View.GONE);
            step2Layout.setVisibility(View.VISIBLE);
            progressStep2.setBackgroundResource(R.drawable.bg_pill_lime);
        });

        btnVerifyOtp.setOnClickListener(v -> {
            if (verifiedEmail.isEmpty()) {
                // The screen was restored without going through step 1 again.
                toast("Please re-enter your email address");
                step2Layout.setVisibility(View.GONE);
                step1Layout.setVisibility(View.VISIBLE);
                progressStep2.setBackgroundResource(R.drawable.bg_pill_muted);
                return;
            }

            String otp = etOtp.getText().toString().trim();
            if (!otp.equals(DEMO_CODE)) {
                toast("That code is not right. Use " + DEMO_CODE + " for now.");
                return;
            }

            step2Layout.setVisibility(View.GONE);
            step3Layout.setVisibility(View.VISIBLE);
            progressStep3.setBackgroundResource(R.drawable.bg_pill_lime);
            toast("Email verified ✅");
        });

        btnResendCode.setOnClickListener(v ->
                toast("Verification code sent to " + verifiedEmail + " 📩"));

        btnCreateAccount.setOnClickListener(v -> createAccount());
    }

    private void createAccount() {
        if (busy) return;

        final String fullName = etFullName.getText().toString().trim();
        final String username = etRegUsername.getText().toString().trim().toLowerCase();
        final String password = etRegPassword.getText().toString();
        final String confirmPassword = etRegConfirmPassword.getText().toString();

        if (verifiedEmail.isEmpty()) {
            toast("Please verify your email address first");
            return;
        }
        if (fullName.isEmpty()) {
            toast("Please enter your full name");
            return;
        }
        if (username.isEmpty()) {
            toast("Pick a username — friends find you with it");
            return;
        }
        if (username.length() < 3) {
            toast("Username must be at least 3 characters");
            return;
        }
        if (!username.matches("[a-z0-9._]+")) {
            toast("Usernames can use letters, numbers, dot and underscore only");
            return;
        }
        if (password.length() < 6) {
            toast("Password must be at least 6 characters");
            return;
        }
        if (!password.equals(confirmPassword)) {
            toast("Passwords do not match");
            return;
        }

        setBusy(true, "creating your account…");

        final FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.createUserWithEmailAndPassword(verifiedEmail, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        setBusy(false, null);
                        toast("Could not create the account: " + LoginActivity.describe(task.getException()));
                        return;
                    }

                    final FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        setBusy(false, null);
                        toast("Could not create the account. Please try again.");
                        return;
                    }

                    // Signed in now, so the username lookup is actually readable.
                    TabRepository.getInstance().getUsersApi()
                            .isUsernameTaken(username, (taken, ownerUid) -> {
                                if (taken) {
                                    rollBackAccount(user,
                                            "That username is taken. Pick another one.");
                                    return;
                                }
                                saveProfile(user, fullName, username);
                            });
                });
    }

    private void saveProfile(FirebaseUser user, String fullName, String username) {
        // Keep the display name on the auth record too, so the login screen has a
        // usable name even if Firestore is briefly unreachable.
        UserProfileChangeRequest profileUpdate = new UserProfileChangeRequest.Builder()
                .setDisplayName(fullName)
                .build();
        user.updateProfile(profileUpdate);

        TabRepository.getInstance().getUsersApi().createProfile(
                user.getUid(),
                verifiedEmail,
                fullName,
                username,
                new FirebaseUsers.ActionResult() {
                    @Override
                    public void onSuccess() {
                        setBusy(false, null);
                        user.sendEmailVerification();

                        FirebaseAuth.getInstance().signOut();
                        toast("Account created! Log in with " + verifiedEmail);
                        startActivity(new Intent(RegisterActivity.this, LoginActivity.class));
                        finish();
                    }

                    @Override
                    public void onFailure(String message) {
                        // The auth account exists but the profile did not save, so
                        // remove the account rather than leaving a half-made user
                        // who cannot log in and cannot re-register either.
                        rollBackAccount(user, "Could not save your profile: " + message);
                    }
                });
    }

    /** Deletes the just-created auth account so the email can be reused cleanly. */
    private void rollBackAccount(FirebaseUser user, String reason) {
        user.delete().addOnCompleteListener(task -> {
            setBusy(false, null);
            toast(reason);
        });
    }

    private void setBusy(boolean isBusy, String label) {
        busy = isBusy;
        if (progressRegister != null) {
            progressRegister.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        }
        btnCreateAccount.setEnabled(!isBusy);
        btnCreateAccount.setAlpha(isBusy ? 0.5f : 1f);
        if (isBusy && label != null) {
            btnCreateAccount.setText(label);
        } else if (!isBusy) {
            btnCreateAccount.setText("create account 🎉");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
