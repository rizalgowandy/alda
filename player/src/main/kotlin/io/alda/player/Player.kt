package io.alda.player

import com.illposed.osc.OSCMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

val playerQueue = LinkedBlockingQueue<List<OSCMessage>>()

val midi = MidiEngine()

val availableChannels = ((0..15).toSet() - 9).toMutableSet()

class Track(val trackNumber : Int) {
  private var _midiChannel : Int? = null
  fun midiChannel() : Int? {
    synchronized(availableChannels) {
      if (_midiChannel == null && !availableChannels.isEmpty()) {
        val channel = availableChannels.first()
        availableChannels.remove(channel)
        _midiChannel = channel
      }
    }

    return _midiChannel
  }

  fun useMidiPercussionChannel() { _midiChannel = 9 }

  val eventBufferQueue = LinkedBlockingQueue<List<Event>>()

  // The set of patterns that are currently looping (whether that be a finite
  // number of times or indefinitely).
  val activePatterns = mutableSetOf<String>()

  fun scheduleMidiPatch (event : MidiPatchEvent, startOffset : Int) {
    midiChannel()?.also { channel ->
      // debug
      println("track ${trackNumber} is channel ${channel}")
      midi.patch(startOffset + event.offset, channel, event.patch)
    } ?: run {
      println("WARN: No MIDI channel available for track ${trackNumber}.")
    }
  }

  fun scheduleMidiNote(event : MidiNoteEvent) {
    midiChannel()?.also { channel ->
      val noteStart = event.offset
      val noteEnd = noteStart + event.audibleDuration
      midi.note(noteStart, noteEnd, channel, event.noteNumber, event.velocity)
    } ?: run {
      println("WARN: No MIDI channel available for track ${trackNumber}.")
    }
  }

  /**
   * Schedules the notes of a pattern, blocking until all iterations of the
   * pattern have been scheduled.
   *
   * Patterns can be looped, and the pattern can be changed while it's looping.
   * When this happens, the change is picked up upon the next iteration of the
   * loop. We accomplish this by scheduling each iteration in a "just in time"
   * manner, i.e. shortly before it is due to be played.
   *
   * @param event An event that specifies a pattern, a relative offset where it
   * should begin, and a number of times to play it.
   * @param _startOffset The absolute offset to which the relative offset is
   * added.
   * @return The list of scheduled notes across all iterations of the pattern.
   */
  fun schedulePattern(event : PatternEvent, _startOffset : Int)
  : List<MidiNoteEvent> {
    var startOffset = _startOffset
    val patternNoteEvents = mutableListOf<MidiNoteEvent>()

    activePatterns.add(event.patternName)

    try {
      for (iteration in 1..event.times) {
        // A loop can be stopped externally by removing the pattern from
        // `activePatterns`. If this happens, we stop looping.
        if (!activePatterns.contains(event.patternName)) break

        println("scheduling iteration $iteration")

        val patternStart = startOffset + event.offset

        // This value is the point in time where we schedule the metamessage
        // that signals the lookup and scheduling of the pattern's events.
        //
        // This scheduling happens shortly before the pattern is to be played.
        val patternSchedule = Math.max(
          startOffset, patternStart - SCHEDULE_BUFFER_TIME_MS
        )

        // This returns a CountDownLatch that starts at 1 and counts down to 0
        // when the `patternSchedule` offset is reached in the sequence.
        val latch = midi.scheduleEvent(patternSchedule, event.patternName)

        // Wait until it's time to look up the pattern's current value and
        // schedule the events.
        println("awaiting latch")
        latch.await()

        println("scheduling pattern ${event.patternName}")

        val pattern = pattern(event.patternName)

        val noteEvents : MutableList<MidiNoteEvent> =
          (pattern.events.filter { it is MidiNoteEvent }
           as MutableList<MidiNoteEvent>)
          .map { it.addOffset(patternStart) } as MutableList<MidiNoteEvent>

        noteEvents.forEach { scheduleMidiNote(it) }
        patternNoteEvents.addAll(noteEvents)

        // Now that we've scheduled at least one iteration, we can start
        // playing. (Unless we've already started playing, in which case this is
        // a no-op.)
        synchronized(midi.isPlaying) {
          if (midi.isPlaying) midi.startSequencer()
        }

        val patternEvents =
          pattern.events.filter { it is PatternEvent }
          as List<PatternEvent>

        // Here, we handle the case where the pattern's events include further
        // pattern events, i.e. the pattern references another pattern.
        //
        // NB: Because of the "just in time" semantics of scheduling patterns,
        // this means we block here until the subpattern is about due to be
        // played.
        patternEvents.forEach { event ->
          patternNoteEvents.addAll(
            schedulePattern(event as PatternEvent, patternStart)
          )
        }

        if (!noteEvents.isEmpty())
          startOffset = noteEvents.map { it.offset + it.duration }.max()!!
      }
    } finally {
      activePatterns.remove(event.patternName)
    }

    return patternNoteEvents
  }

  private fun adjustStartOffset(_startOffset : Int) : Int {
    var startOffset = _startOffset

    val now = Math.round(midi.currentOffset()).toInt()

    // If we're not scheduling into the future, then whatever we're supposed to
    // be scheduling should happen ASAP.
    if (startOffset < now) startOffset = now

    // Ensure that there is time to schedule the events before they're due to
    // come up in the sequence.
    if (midi.isPlaying && (startOffset - now < SCHEDULE_BUFFER_TIME_MS))
      startOffset += SCHEDULE_BUFFER_TIME_MS

    return startOffset
  }

  fun scheduleEvents(events : List<Event>, _startOffset : Int) : Int {
    val startOffset = adjustStartOffset(_startOffset)

    events.filter { it is MidiPatchEvent }.forEach {
      scheduleMidiPatch(it as MidiPatchEvent, startOffset)
    }

    events.filter { it is MidiPercussionEvent }.forEach {
      midi.percussion(
        startOffset + (it as MidiPercussionEvent).offset, trackNumber
      )
    }

    val noteEvents =
      (events.filter { it is MidiNoteEvent } as MutableList<MidiNoteEvent>)
        .map { it.addOffset(startOffset) } as MutableList<MidiNoteEvent>

    noteEvents.forEach { scheduleMidiNote(it) }

    events.forEach { event ->
      when (event) {
        is PatternLoopEvent -> {
          // TODO
        }
      }
    }

    // Patterns can include other patterns, and to support dynamically changing
    // pattern contents on the fly, we look up each pattern's contents shortly
    // before it is scheduled to play. This means that the total number of
    // patterns can change at a moment's notice.

    // For each pattern event, we...
    // * wait until right before the pattern is supposed to be played
    // * look up the pattern
    // * schedule the pattern's events
    // * add the pattern's events to `noteEvents`
    events.filter { it is PatternEvent }.forEach {
      val event = it as PatternEvent
      noteEvents.addAll(schedulePattern(event, startOffset))
    }

    // Now that all the notes have been scheduled, we can start the sequencer
    // (assuming it hasn't been started already, in which case this is a no-op).
    synchronized(midi.isPlaying) {
      if (midi.isPlaying) midi.startSequencer()
    }

    // At this point, `noteEvents` should contain all of the notes we've
    // scheduled, including the values of patterns at the moment right before
    // they were scheduled.
    //
    // We can now calculate the latest note end offset, which shall be our new
    // `startOffset`.

    if (noteEvents.isEmpty())
      return _startOffset

    return noteEvents.map { it.offset + it.duration }.max()!!
  }

  init {
    // This thread schedules events on this track.
    thread {
      // Before we can schedule these events, we need to know the start offset.
      //
      // This can change dynamically, e.g. if a pattern is changed on-the-fly
      // during playback, so we defer scheduling the next buffer of events as
      // long as we can.
      //
      // When new events come in on the `eventsBufferQueue`, it may be the case
      // that previous events are still lined up to be scheduled (e.g. a pattern
      // is looping). When this is the case, the new events wait in line until
      // the previous scheduling has completed and the offset where the next
      // events should start is updated.
      var startOffset = 0
      var scheduling = ReentrantLock(true) // fairness enabled

      while (!Thread.currentThread().isInterrupted()) {
        try {
          val events = eventBufferQueue.take()

          events.filter { it is FinishLoopEvent }.forEach {
            thread {
              val event = it as FinishLoopEvent
              val offset = adjustStartOffset(startOffset) + event.offset
              val latch = midi.scheduleEvent(offset, "FinishLoop")
              latch.await()
              println("clearing active patterns")
              activePatterns.clear()
            }
          }

          // We start a new thread here so that we can wait for the opportunity
          // to schedule new events, while the parent thread continues to
          // receive new events on the queue.
          thread {
            // Wait for the previous scheduling of events to finish.
            scheduling.lock()
            println("TRACK ${trackNumber}: startOffset is ${startOffset}")
            try {
              // Schedule events and update `startOffset` to be the offset at
              // which the next events should start (after the ones we're
              // scheduling here).
              startOffset = scheduleEvents(events, startOffset)
            } finally {
              scheduling.unlock()
            }
          }
        } catch (iex : InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
    }
  }
}

val tracks = mutableMapOf<Int, Track>()

fun track(trackNumber: Int): Track {
  if (!tracks.containsKey(trackNumber))
    tracks.put(trackNumber, Track(trackNumber))

  return tracks.get(trackNumber)!!
}

class Pattern() {
  val events = mutableListOf<Event>()
}

val patterns = mutableMapOf<String, Pattern>()

fun pattern(patternName: String): Pattern {
  if (!patterns.containsKey(patternName))
    patterns.put(patternName, Pattern())

  return patterns.get(patternName)!!
}

private fun applyUpdates(updates : Updates) {
  // debug
  println("----")
  println(updates.systemActions)
  println(updates.trackActions)
  println(updates.trackEvents)
  println(updates.patternActions)
  println(updates.patternEvents)
  println("----")

  // PHASE 1: stop/mute/clear

  if (updates.systemActions.contains(SystemAction.STOP))
    midi.stopSequencer()

  if (updates.systemActions.contains(SystemAction.CLEAR)) {
    // TODO
  }

  updates.trackActions.forEach { (trackNumber, actions) ->
    if (actions.contains(TrackAction.MUTE)) {
      // TODO
    }

    if (actions.contains(TrackAction.CLEAR)) {
      // TODO
    }
  }

  updates.patternActions.forEach { (patternName, actions) ->
    if (actions.contains(PatternAction.CLEAR)) {
      pattern(patternName).events.clear()
    }
  }

  // PHASE 2: update patterns

  updates.patternEvents.forEach { (patternName, events) ->
    pattern(patternName).events.addAll(events)
  }

  // PHASE 3: update tracks

  updates.trackEvents.forEach { (trackNumber, events) ->
    track(trackNumber).eventBufferQueue.put(events)
  }

  // PHASE 4: unmute/play

  updates.trackActions.forEach { (trackNumber, actions) ->
    if (actions.contains(TrackAction.UNMUTE)) {
      // TODO
    }
  }

  // NB: We don't actually start the sequencer here; that action needs to be
  // deferred until after a track thread finishes scheduling a buffer of events.
  if (updates.systemActions.contains(SystemAction.PLAY))
    midi.isPlaying = true
}

fun player() : Thread {
  return thread(start = false) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        val instructions = playerQueue.take()
        val updates = parseUpdates(instructions)
        applyUpdates(updates)
      } catch (iex : InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }
}
