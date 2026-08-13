package com.example.ds04_monash_rideshare_application.Database.Messaging;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.example.ds04_monash_rideshare_application.Database.User.UserAccount;

@Entity(
        tableName = "message",
        foreignKeys = {
                @ForeignKey(
                        entity = Conversation.class,
                        parentColumns = "conversationId",
                        childColumns = "conversationId",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = UserAccount.class,
                        parentColumns = "userId",
                        childColumns = "senderId",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {
                @Index("conversationId"),
                @Index("senderId")
        }
)
public class Message {
    @PrimaryKey
    @NonNull
    private String messageId;

    @NonNull
    private String conversationId;

    @NonNull
    private String senderId;

    private String body;
    private long sentAt;
    private String deliveryStatus;

    public Message(@NonNull String messageId, @NonNull String conversationId, @NonNull String senderId,
                   String body, long sentAt, String deliveryStatus) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.body = body;
        this.sentAt = sentAt;
        this.deliveryStatus = deliveryStatus;
    }

    @NonNull public String getMessageId() { return messageId; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getSenderId() { return senderId; }
    public String getBody() { return body; }
    public long getSentAt() { return sentAt; }
    public String getDeliveryStatus() { return deliveryStatus; }
}