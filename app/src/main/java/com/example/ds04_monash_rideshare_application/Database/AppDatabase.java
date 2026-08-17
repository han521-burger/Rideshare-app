package com.example.ds04_monash_rideshare_application.Database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.example.ds04_monash_rideshare_application.Database.Driver.DriverApplication;
import com.example.ds04_monash_rideshare_application.Database.Driver.Vehicle;
import com.example.ds04_monash_rideshare_application.Database.Messaging.Conversation;
import com.example.ds04_monash_rideshare_application.Database.Messaging.Message;
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest;
import com.example.ds04_monash_rideshare_application.Database.RideShare.EmissionEstimate;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOfferDao;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshotDao;
import com.example.ds04_monash_rideshare_application.Database.User.UserAccount;
import com.example.ds04_monash_rideshare_application.Database.User.UserAccountDao;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile;
import com.example.ds04_monash_rideshare_application.Database.User.UserProfileDao;

//The local cache described in the proposal - not the source of truth for anything that lives on a
//server. UI code must never touch a Dao from here directly; go through a Repository (see the
//Repository package), which decides whether data comes from this cache or from the API.
//
//Entities that carry sensitive personal evidence (DriverApplication.rejectionReason, Message.body,
//Vehicle's masked registration) are kept in the schema for foreign-key integrity but deliberately
//have no Dao/Repository exposing a general-purpose cache API yet - only what the proposal calls out
//(user profile, ride list, confirmed rides, trip history, route summaries) is wired up so far.
@Database(
        entities = {
                UserAccount.class,
                UserProfile.class,
                Vehicle.class,
                DriverApplication.class,
                RideOffer.class,
                RouteSnapshot.class,
                EmissionEstimate.class,
                SeatRequest.class,
                Conversation.class,
                Message.class
        },
        version = 2,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract UserAccountDao userAccountDao();
    public abstract UserProfileDao userProfileDao();
    public abstract RideOfferDao rideOfferDao();
    public abstract RouteSnapshotDao routeSnapshotDao();

    public static final Executor databaseWriteExecutor = Executors.newFixedThreadPool(2);

    private static volatile AppDatabase instance;

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    // This is a cache (see the class comment) - if its schema changes, wiping and
                    // rebuilding it locally is harmless, so no real Migration is needed here.
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "ds04_rideshare.db"
                    ).fallbackToDestructiveMigration().build();
                }
            }
        }
        return instance;
    }
}
