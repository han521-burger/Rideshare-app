package com.example.ds04_monash_rideshare_application.Database.Driver;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import com.example.ds04_monash_rideshare_application.Database.User.UserAccount;

@Entity(
        tableName = "driver_application",
        foreignKeys = {
                @ForeignKey(
                        entity = UserAccount.class,
                        parentColumns = "userId",
                        childColumns = "userId",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = UserAccount.class,
                        parentColumns = "userId",
                        childColumns = "reviewerId",
                        onDelete = ForeignKey.SET_NULL
                )
        },
        indices = {
                @Index("userId"),
                @Index("reviewerId")
        }
)
public class DriverApplication {
    @PrimaryKey
    @NonNull
    private String applicationId;

    @NonNull
    private String userId;

    private String status;
    private long submittedAt;

    @Nullable
    private Long reviewedAt;

    @Nullable
    private String reviewerId;

    @Nullable
    private String rejectionReason;

    public DriverApplication(@NonNull String applicationId, @NonNull String userId, String status,
                             long submittedAt, @Nullable Long reviewedAt, @Nullable String reviewerId,
                             @Nullable String rejectionReason) {
        this.applicationId = applicationId;
        this.userId = userId;
        this.status = status;
        this.submittedAt = submittedAt;
        this.reviewedAt = reviewedAt;
        this.reviewerId = reviewerId;
        this.rejectionReason = rejectionReason;
    }

    @NonNull public String getApplicationId() {
        return applicationId; }
    @NonNull public String getUserId() {
        return userId; }
    public String getStatus() {
        return status; }
    public long getSubmittedAt() {
        return submittedAt; }
    @Nullable public Long getReviewedAt() {
        return reviewedAt; }
    @Nullable public String getReviewerId() {
        return reviewerId; }
    @Nullable public String getRejectionReason() {
        return rejectionReason; }
}