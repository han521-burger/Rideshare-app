package com.example.ds04_monash_rideshare_application.Database.User;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
//Entity creation by telling ROOM to create an SQL
@Entity(
        tableName = "user_account",
        indices = {
                @Index(value = {"externalSubjectId"}, unique = true),
                @Index(value = {"emailAccount"}, unique = true)
        }
)
public class UserAccount {
    @PrimaryKey
    @NonNull
    public String userId;
    //External Subject ID Takes into Account OATH / Monash SSO
    public String externalSubjectId;
    public String emailAccount;
    public String accountStatus;
    public long createdAt;

    public UserAccount(@NonNull String userId , String externalSubjectId , String emailAccount , String accountStatus , long createdAt){
        this.userId = userId;
        this.externalSubjectId = externalSubjectId;
        this.emailAccount = emailAccount;
        this.accountStatus= accountStatus;
        this.createdAt = createdAt;
    }
//Accessors and Mutators
    @NonNull
    public String getUserId(){
        return userId;
    }
    public void setUserId(@NonNull String userId){
        this.userId = userId;
    }

    public String getExternalSubjectId(){
        return externalSubjectId;
    }
    public void setExternalSubjectId(String externalSubjectId){
        this.externalSubjectId = externalSubjectId;
    }
    public String getEmailAccount(){
        return emailAccount;
    }
    public void setEmailAccount(String emailAccount){
        this.emailAccount = emailAccount;
    }
    public String getAccountStatus(){
        return accountStatus;
    }
    public void setAccountStatus(String accountStatus){
        this.accountStatus = accountStatus;
    }
    public long getCreatedAt(){
        return createdAt;
    }
    public void setCreatedAt(long createdAt){
        this.createdAt = createdAt;
    }

}
