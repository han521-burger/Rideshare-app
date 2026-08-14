package com.example.ds04_monash_rideshare_application.Auth;

//Abstraction over "how the user signs in" so the rest of the app never depends on Monash SSO directly.
//Swap FakeAuthenticationProvider for MonashAuthenticationProvider once Monash SSO client access is approved.
public interface AuthenticationProvider {

    void login(AuthCallback callback);

    void logout(AuthCallback callback);

    AuthenticatedUser getCurrentUser();

    interface AuthCallback {
        void onSuccess(AuthenticatedUser user);
        void onFailure(Exception error);
    }
}
