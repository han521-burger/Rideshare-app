package com.example.ds04_monash_rideshare_application.Database.RideShare;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

//Room only. Nothing outside Repository/RouteRepository.java should call this directly.
@Dao
public interface RouteSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<RouteSnapshot> snapshots);

    @Query("SELECT * FROM route_snapshot WHERE rideId = :rideId ORDER BY calculatedAt DESC")
    LiveData<List<RouteSnapshot>> observeByRideId(String rideId);
}
