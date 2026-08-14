package com.example.ds04_monash_rideshare_application.Repository;

import androidx.lifecycle.LiveData;

import java.util.concurrent.Executor;

import com.example.ds04_monash_rideshare_application.Database.User.UserProfile;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfileDao;
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService;

//The only class the UI should ask for user profile data - never UserProfileDao directly.
//getUserProfile() always reads from the Room cache; refreshUserProfile() pulls from the API and
//writes the result back into Room, so Room stays the single source of truth for observers.
public class UserRepository {
    private final UserProfileDao userProfileDao;
    private final RideApiService apiService;
    private final Executor ioExecutor;

    public UserRepository(UserProfileDao userProfileDao, RideApiService apiService, Executor ioExecutor) {
        this.userProfileDao = userProfileDao;
        this.apiService = apiService;
        this.ioExecutor = ioExecutor;
    }

    public LiveData<UserProfile> getUserProfile(String userId) {
        return userProfileDao.observeByUserId(userId);
    }

    public void refreshUserProfile(String userId) {
        apiService.fetchUserProfile(userId, new RideApiService.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile result) {
                if (result != null) {
                    ioExecutor.execute(() -> userProfileDao.upsert(result));
                }
            }

            @Override
            public void onFailure(Exception error) {
                //Room keeps serving the last-known-good profile; nothing to do here.
            }
        });
    }
}
