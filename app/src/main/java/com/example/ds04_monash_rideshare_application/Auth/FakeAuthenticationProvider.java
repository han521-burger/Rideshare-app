package com.example.ds04_monash_rideshare_application.Auth;

//Dev-only stand-in for Monash SSO. Lets the rest of the app (rideshare, driver, messaging) be built
//and tested before Monash eSolutions approves an OIDC client registration.
//Not for production use - swap this out for MonashAuthenticationProvider once SSO access is granted.
public class FakeAuthenticationProvider implements AuthenticationProvider {

    private AuthenticatedUser currentUser;

    @Override
    public void login(AuthCallback callback) {
        currentUser = new AuthenticatedUser(
                "fake-user-001",
                "fake-subject-001",
                "test.student@student.monash.edu",
                "Test Student"
        );
        if (callback != null) {
            callback.onSuccess(currentUser);
        }
    }

    @Override
    public void logout(AuthCallback callback) {
        currentUser = null;
        if (callback != null) {
            callback.onSuccess(null);
        }
    }

    @Override
    public AuthenticatedUser getCurrentUser() {
        return currentUser;
    }
}
