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
    emergencyContactName = this[Users.emergencyContactName],
    emergencyContactPhone = this[Users.emergencyContactPhone],
    createdAt = this[Users.createdAt]
)

fun ResultRow.toUserSummaryDto(averageRating: Double? = null, ratingCount: Int = 0) = UserSummaryDto(
    userId = this[Users.userId],
    displayName = this[Users.displayName],
    photoRef = this[Users.photoRef],
    phoneNumber = this[Users.phoneNumber],
    averageRating = averageRating,
    ratingCount = ratingCount
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
    fuelType = this[Vehicles.fuelType],
    vehicleType = this[Vehicles.vehicleType],
    colour = this[Vehicles.colour]
)

fun ResultRow.toRideStopDto() = RideStopDto(
    stopId = this[RideStops.stopId],
    sequence = this[RideStops.sequence],
    lat = this[RideStops.lat],
    lng = this[RideStops.lng],
    label = this[RideStops.label]
)

// driver/vehicle/stops are optional enrichment - callers that already batch-loaded them (to
// avoid N+1 queries) pass them in; callers that don't care just get the bare ride.
fun ResultRow.toRideDto(
    matchScore: MatchScoreDto? = null,
    driver: UserSummaryDto? = null,
    vehicle: VehicleDto? = null,
    stops: List<RideStopDto> = emptyList()
) = RideDto(
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
    driverEnRoute = this[Rides.driverEnRoute],
    recurringGroupId = this[Rides.recurringGroupId],
    stops = stops,
    driver = driver,
    vehicle = vehicle,
    matchScore = matchScore
)

fun ResultRow.toRideRequestDto() = RideRequestDto(
    requestId = this[RideRequests.requestId],
    rideId = this[RideRequests.rideId],
    passengerId = this[RideRequests.passengerId],
    seatsRequested = this[RideRequests.seatsRequested],
    status = this[RideRequests.status],
    note = this[RideRequests.note],
    createdAt = this[RideRequests.createdAt],
    respondedAt = this[RideRequests.respondedAt],
    originLat = this[RideRequests.originLat],
    originLng = this[RideRequests.originLng],
    destinationLat = this[RideRequests.destinationLat],
    destinationLng = this[RideRequests.destinationLng],
    isAsap = this[RideRequests.isAsap],
    desiredDepartureTime = this[RideRequests.desiredDepartureTime],
    repeatDaysOfWeek = this[RideRequests.repeatDaysOfWeek]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
    repeatWeeks = this[RideRequests.repeatWeeks]
)

fun ResultRow.toMessageDto() = MessageDto(
    messageId = this[Messages.messageId],
    rideId = this[Messages.rideId],
    senderId = this[Messages.senderId],
    body = this[Messages.body],
    sentAt = this[Messages.sentAt]
)

fun ResultRow.toRatingDto() = RatingDto(
    ratingId = this[Ratings.ratingId],
    rideId = this[Ratings.rideId],
    raterId = this[Ratings.raterId],
    ratedUserId = this[Ratings.ratedUserId],
    stars = this[Ratings.stars],
    tags = this[Ratings.tags]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    comment = this[Ratings.comment],
    createdAt = this[Ratings.createdAt]
)
