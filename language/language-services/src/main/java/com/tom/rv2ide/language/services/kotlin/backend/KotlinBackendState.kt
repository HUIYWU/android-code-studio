package com.tom.rv2ide.language.services.kotlin.backend

enum class KotlinBackendState {
  NEW,
  STARTING,
  READY,
  REFRESHING,
  FAILED,
  CLOSED,
}
