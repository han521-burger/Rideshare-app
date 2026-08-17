package com.example.ds04_monash_rideshare_application.Database.User;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

// No foreign key onto user_account here on purpose - see RideOffer.java's header comment for why
// (this is a partial cache of backend data, not a fully-mirrored local copy).
@Entity(tableName = "user_profile")
public class UserProfile {
    @PrimaryKey
    @NonNull
    private String userId;
    private String displayName;
    private String preferredName;
    @Nullable
    private String phoneNumber;
    @Nullable
    private String photoRef;
    private boolean passengerEnabled;
    public UserProfile(@NonNull String userId, String displayName , String preferredName, @Nullable String phoneNumber , @Nullable String photoRef, boolean passengerEnabled){
        this.userId = userId;
        this.displayName = displayName;
        this.preferredName= preferredName;
        this.phoneNumber = phoneNumber;
        this.photoRef = photoRef;
        this.passengerEnabled=passengerEnabled;
    }
    @NonNull
    public String getUserId(){
        return userId;
    }
    public void setUserId(@NonNull String userId){
        this.userId = userId;
    }
    public String getDisplayName(){
        return displayName;
    }
    public void setDisplayName(String displayName){
        this.displayName = displayName;
    }
    public String getPreferredName(){
        return preferredName;
    }
    public void setPreferredName(String preferredName){
        this.preferredName = preferredName;
    }
    @Nullable
    public String getPhoneNumber(){
        return phoneNumber;
    }
    public void setPhoneNumber(@Nullable String phoneNumber){
        this.phoneNumber = phoneNumber;
    }
    @Nullable
    public String getPhotoRef(){
        return photoRef;
    }
    public void setPhotoRef(@Nullable String photoRef){
        this.photoRef = photoRef;
    }
    public boolean isPassengerEnabled(){
        return passengerEnabled;
    }
    public void setPassengerEnabled(boolean passengerEnabled){
        this.passengerEnabled = passengerEnabled;
    }
}

