package com.example.ds04_monash_rideshare_application.Database.RideShare;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

// No foreign key onto ride_offer here on purpose - see RideOffer.java's header comment. Route
// snapshots and their ride can arrive as separate, unordered cache writes.
@Entity(
        tableName = "route_snapshot",
        indices = @Index("rideId")
)
public class RouteSnapshot {
    @PrimaryKey
    @NonNull
    private String routeId;

    @NonNull
    private String rideId;

    private String originLatLng;
    private String destinationLatLng;
    private long distanceM;
    private long durationS;
    private String routeVendor;
    private long calculatedAt;

    public RouteSnapshot(@NonNull String routeId, @NonNull String rideId, String originLatLng,
                         String destinationLatLng, long distanceM, long durationS,
                         String routeVendor, long calculatedAt) {
        this.routeId = routeId;
        this.rideId = rideId;
        this.originLatLng = originLatLng;
        this.destinationLatLng = destinationLatLng;
        this.distanceM = distanceM;
        this.durationS = durationS;
        this.routeVendor = routeVendor;
        this.calculatedAt = calculatedAt;
    }

    @NonNull public String getRouteId() {
        return routeId; }
    @NonNull public String getRideId() {
        return rideId; }
    public String getOriginLatLng() {
        return originLatLng; }
    public String getDestinationLatLng() {
        return destinationLatLng; }
    public long getDistanceM() {
        return distanceM; }
    public long getDurationS() {
        return durationS; }
    public String getRouteVendor() {
        return routeVendor; }
    public long getCalculatedAt() {
        return calculatedAt; }
}