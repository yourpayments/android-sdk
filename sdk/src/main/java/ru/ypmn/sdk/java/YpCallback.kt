package ru.ypmn.sdk.java
interface YpCallback<T> { fun onSuccess(result: T); fun onError(error: Throwable) }
