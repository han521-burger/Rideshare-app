package com.example.ds04_monash_rideshare_application.Auth;

//Represents the currently signed-in user, independent of which AuthenticationProvider produced it
public class AuthenticatedUser {
    public String userId;
    public String externalSubjectId;
    public String emailAccount;
    public String displayName;

    public AuthenticatedUser(String userId, String externalSubjectId, String emailAccount, String displayName) {
        this.userId = userId;
        this.externalSubjectId = externalSubjectId;
        this.emailAccount = emailAccount;
        this.displayName = displayName;
    }

    public String getUserId() {
        return userId;
    }
    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getExternalSubjectId() {
        return externalSubjectId;
    }
    public void setExternalSubjectId(String externalSubjectId) {
        this.externalSubjectId = externalSubjectId;
    }

    public String getEmailAccount() {
        return emailAccount;
    }
    public void setEmailAccount(String emailAccount) {
        this.emailAccount = emailAccount;
    }

    public String getDisplayName() {
        return displayName;
    }
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
