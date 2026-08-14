package com.example.ds04_monash_rideshare_application.Repository;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.Executor;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshot;
import com.example.ds04_monash_rideshare_application.Database.RideShare.RouteSnapshotDao;
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService;

//The only class the UI should ask for route summaries - never RouteSnapshotDao directly.
//getRouteSummaries() always reads from Room; refreshRouteSummaries() pulls from the API (e.g. the
//Yandex driving-route result) and writes it back into Room.
public class RouteRepository {
    private final RouteSnapshotDao routeSnapshotDao;
    private final RideApiService apiService;
    private final Executor ioExecutor;

    public RouteRepository(RouteSnapshotDao routeSnapshotDao, RideApiService apiService, Executor ioExecutor) {
        this.routeSnapshotDao = routeSnapshotDao;
        this.apiService = apiService;
        this.ioExecutor = ioExecutor;
    }

    public LiveData<List<RouteSnapshot>> getRouteSummaries(String rideId) {
        return routeSnapshotDao.observeByRideId(rideId);
    }

    public void refreshRouteSummaries(String rideId) {
        apiService.fetchRouteSummaries(rideId, new RideApiService.Callback<List<RouteSnapshot>>() {
            @Override
            public void onSuccess(List<RouteSnapshot> result) {
                ioExecutor.execute(() -> routeSnapshotDao.upsertAll(result));
            }

            @Override
            public void onFailure(Exception error) {
                //Room keeps serving the last-known-good route summaries; nothing to do here.
            }
        });
    }
}
