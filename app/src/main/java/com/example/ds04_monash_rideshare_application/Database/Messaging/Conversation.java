package com.example.ds04_monash_rideshare_application.Database.Messaging;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer;

@Entity(
        tableName = "conversation",
        foreignKeys = @ForeignKey(
                entity = RideOffer.class,
                parentColumns = "rideId",
                childColumns = "rideId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = @Index("rideId")
)
public class Conversation {
    @PrimaryKey
    @NonNull
    private String conversationId;

    @NonNull
    private String rideId;

    private String participantIds;
    private String status;

    public Conversation(@NonNull String conversationId, @NonNull String rideId,
                        String participantIds, String status) {
        this.conversationId = conversationId;
        this.rideId = rideId;
        this.participantIds = participantIds;
        this.status = status;
    }

    @NonNull public String getConversationId() {
        return conversationId; }
    @NonNull public String getRideId() {
        return rideId; }
    public String getParticipantIds() {
        return participantIds; }
    public String getStatus() {
        return status; }
}