package com.example.tab_able;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.tab_able.data.TabRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmail, etPassword;
    private TextView btnLogin, btnGoRegister, btnForgotPassword;
    private View progress;

    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();

        etEmail = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        btnGoRegister = findViewById(R.id.btn_go_register);
        btnForgotPassword = findViewById(R.id.btn_forgot_password);
        progress = findViewById(R.id.progress_login);

        btnLogin.setOnClickListener(v -> attemptLogin());
        btnForgotPassword.setOnClickListener(v -> sendPasswordReset());
        btnGoRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));

        // Already signed in? Go straight in, but only after the profile has been
        // read back from Firestore so the app opens with the user's real name and
        // username rather than a guess derived from their email address.
        FirebaseUser existing = auth.getCurrentUser();
        if (existing != null) {
            showProgress(true, "restoring your profile…");
            completeSignIn(existing, true);
        }
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim().toLowerCase();
        String password = etPassword.getText().toString();

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("Please enter a valid email address");
            return;
        }
        if (password.isEmpty()) {
            toast("Please enter your password");
            return;
        }

        showProgress(true, "signing in…");
        auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        showProgress(false, null);
                        toast("Login failed: " + describe(task.getException()));
                        return;
                    }
                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        showProgress(false, null);
                        toast("Login failed. Please try again.");
                        return;
                    }
                    completeSignIn(user, false);
                });
    }

    /**
     * Pushes the account into the repository, reloads the persisted profile from
     * Firestore, then opens the dashboard. This is the step that makes a returning
     * user get their own name, username and balances back instead of a blank slate.
     */
    private void completeSignIn(FirebaseUser user, boolean fromSavedSession) {
        TabRepository repo = TabRepository.getInstance();
        String email = user.getEmail() != null ? user.getEmail() : "";
        String fallbackName = user.getDisplayName();
        if (fallbackName == null || fallbackName.isEmpty()) {
            fallbackName = email.contains("@") ? email.split("@")[0] : "User";
        }

        repo.setCurrentUser(user.getUid(), fallbackName, email);

        repo.getUsersApi().loadProfile(user.getUid(), () -> runOnUiThread(() -> {
            // Now that the real profile is in memory, make sure the document and its
            // email/username lookup keys exist. Done here (not before the load) so an
            // existing username is never overwritten by the email-derived guess.
            repo.syncProfile();
            repo.repairLookupIndex();

            showProgress(false, null);

            if (!fromSavedSession) {
                toast("Welcome back, " + repo.getCurrentUserName() + " 💸");
            }

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }));
    }

    private void sendPasswordReset() {
        String email = etEmail.getText().toString().trim().toLowerCase();
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("Type your email above first, then tap forgot password");
            return;
        }

        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(unused ->
                        toast("Password reset email sent to " + email + " ✉️"))
                .addOnFailureListener(e ->
                        toast("Could not send reset email: " + describe(e)));
    }

    private void showProgress(boolean visible, String message) {
        if (progress != null) {
            progress.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        btnLogin.setEnabled(!visible);
        btnLogin.setAlpha(visible ? 0.5f : 1f);
        if (visible && message != null) {
            btnLogin.setText(message);
        } else if (!visible) {
            btnLogin.setText("log in 💸");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** Turns Firebase's exception text into something a person can act on. */
    static String describe(Exception e) {
        if (e == null || e.getMessage() == null) return "Unknown error";
        String raw = e.getMessage();

        if (raw.contains("INVALID_LOGIN_CREDENTIALS")
                || raw.contains("password is invalid")
                || raw.contains("no user record")) {
            return "wrong email or password";
        }
        if (raw.contains("network error") || raw.contains("Unable to resolve host")) {
            return "no internet connection";
        }
        if (raw.contains("too many requests") || raw.contains("TOO_MANY_ATTEMPTS")) {
            return "too many attempts, try again later";
        }
        if (raw.contains("configuration") || raw.contains("CONFIGURATION_NOT_FOUND")) {
            return "Email/Password sign-in must be enabled in the Firebase console";
        }
        if (raw.contains("email address is already in use")) {
            return "that email already has an account";
        }
        if (raw.contains("badly formatted")) {
            return "that email address looks invalid";
        }
        return raw;
    }
}
