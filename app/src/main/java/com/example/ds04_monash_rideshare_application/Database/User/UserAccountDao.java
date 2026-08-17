package com.example.ds04_monash_rideshare_application.Database.User;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;

//Room only. UserProfile has a foreign key onto user_account(userId), so a UserAccount row must
//exist here before any UserProfile row referencing it can be inserted - see RideshareViewModel's
//sign-in flow, which upserts this first.
@Dao
public interface UserAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(UserAccount account);
}
