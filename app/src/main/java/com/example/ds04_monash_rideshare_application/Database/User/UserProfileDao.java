package com.example.ds04_monash_rideshare_application.Database.User;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

//Room only. Nothing outside Repository/UserRepository.java should call this directly.
@Dao
public interface UserProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(UserProfile profile);

    @Query("SELECT * FROM user_profile WHERE userId = :userId LIMIT 1")
    LiveData<UserProfile> observeByUserId(String userId);
}
