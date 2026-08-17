package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toUserDto() = UserDto(
    userId = this[Users.userId],
    externalSubjectId = this[Users.externalSubjectId],
    emailAccount = this[Users.emailAccount],
    displayName = this[Users.displayName],
    preferredName = this[Users.preferredName],
    phoneNumber = this[Users.phoneNumber],
    photoRef = this[Users.photoRef],
    accountStatus = this[Users.accountStatus],
    passengerEnabled = this[Users.passengerEnabled],
    createdAt = this[Users.createdAt]
)

fun ResultRow.toVehicleDto() = VehicleDto(
    vehicleId = this[Vehicles.vehicleId],
    ownerId = this[Vehicles.ownerId],
    year = this[Vehicles.year],
    make = this[Vehicles.make],
    model = this[Vehicles.model],
    variant = this[Vehicles.variant],
    registrationMasked = this[Vehicles.registrationMasked],
    seatCapacity = this[Vehicles.seatCapacity],
    fuelType = this[Vehicles.fuelType]
)

fun ResultRow.toRideDto(matchScore: MatchScoreDto? = null) = RideDto(
    rideId = this[Rides.rideId],
    driverId = this[Rides.driverId],
    vehicleId = this[Rides.vehicleId],
    originLat = this[Rides.originLat],
    originLng = this[Rides.originLng],
    destinationLat = this[Rides.destinationLat],
    destinationLng = this[Rides.destinationLng],
    distanceMeters = this[Rides.distanceMeters],
    estimatedDurationSeconds = this[Rides.estimatedDurationSeconds],
    departureTime = this[Rides.departureTime],
    seatsTotal = this[Rides.seatsTotal],
    seatsAvailable = this[Rides.seatsAvailable],
    notes = this[Rides.notes],
    status = this[Rides.status],
    createdAt = this[Rides.createdAt],
    matchScore = matchScore
)

fun ResultRow.toRideRequestDto() = RideRequestDto(
    requestId = this[RideRequests.requestId],
    rideId = this[RideRequests.rideId],
    passengerId = this[RideRequests.passengerId],
    seatsRequested = this[RideRequests.seatsRequested],
    status = this[RideRequests.status],
    createdAt = this[RideRequests.createdAt],
    respondedAt = this[RideRequests.respondedAt]
)

fun ResultRow.toMessageDto() = MessageDto(
    messageId = this[Messages.messageId],
    rideId = this[Messages.rideId],
    senderId = this[Messages.senderId],
    body = this[Messages.body],
    sentAt = this[Messages.sentAt]
)
