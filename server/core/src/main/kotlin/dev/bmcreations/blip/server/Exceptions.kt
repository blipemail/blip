package dev.bmcreations.blip.server

class NotFoundException(message: String) : RuntimeException(message)
class ForbiddenException(message: String) : RuntimeException(message)
class UnauthorizedException(message: String) : RuntimeException(message)
class TierLimitException(message: String) : RuntimeException(message)
