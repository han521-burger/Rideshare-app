package com.example.ds04_monash_rideshare_application.Database.RideShare;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

//Room only. Nothing outside Repository/RideRepository.java should call this directly.
@Dao
public interface RideOfferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<RideOffer> rides);

    @Query("SELECT * FROM ride_offer WHERE status = :status ORDER BY departureTime DESC")
    LiveData<List<RideOffer>> observeByStatus(String status);
}
