package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.artificial.agent.NativeFunctionCallResponse
import com.tom.rv2ide.artificial.agent.NativeFunctionCall

/**
 * Deterministic [ProviderCall] double for [AgentController] JVM tests.
 *
 * Returns the queued replies in order and records every prompt, so a test can
 * assert on the assembled prompt without calling `buildPrompt()` directly.
 * An empty queue is a failure rather than a silent repeat: a test that
 * over- or under-runs the loop then fails loudly instead of passing by accident.
 */
class FakeProviderCall(
    replies: List<String> = emptyList(),
    nativeReplies: List<NativeFunctionCallResponse> = emptyList()
) : ProviderCall {

  private val queue = replies.toMutableList()
  private val nativeQueue = nativeReplies.toMutableList()

  /** Every prompt handed to the provider, in call order. */
  val prompts = mutableListOf<String>()

  /** True once the queue was drained and a later call had to fail. */
  var exhausted: Boolean = false
    private set

  override suspend fun generateCode(
      prompt: String,
      language: String,
      projectStructure: String?
  ): Result<String> {
    prompts.add(prompt)
    if (queue.isEmpty()) {
      exhausted = true
      return Result.failure(IllegalStateException("FakeProviderCall: no replies left"))
    }
    return Result.success(queue.removeAt(0))
  }

  override suspend fun generateWithFunctions(
      prompt: String,
      functionDeclarations: List<Any>,
      language: String,
      projectStructure: String?
  ): Result<NativeFunctionCallResponse> {
    prompts.add(prompt)
    if (nativeQueue.isEmpty()) {
      exhausted = true
      return Result.failure(IllegalStateException("FakeProviderCall: no native replies left"))
    }
    return Result.success(nativeQueue.removeAt(0))
  }

  override val providerName: String = "fake"
}
