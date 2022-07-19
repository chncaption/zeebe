/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.streamprocessor;

import static io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor.HEADER_BLOCK_LENGTH;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;

import io.camunda.zeebe.logstreams.impl.log.LogEntryDescriptor;
import io.camunda.zeebe.protocol.Protocol;
import io.camunda.zeebe.protocol.impl.record.RecordMetadata;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.Intent;
import io.camunda.zeebe.util.buffer.BufferWriter;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;

/** Stream writer that writes into a byte buffer instead of writing directly to the stream */
final class BufferedStreamWriter {

  private static final int INITIAL_BUFFER_CAPACITY = 1024 * 32;

  // todo we can allocate one buffer with max message size here and then it would simplify this -
  // and limit checking
  private final MutableDirectBuffer eventBuffer =
      new ExpandableDirectByteBuffer(INITIAL_BUFFER_CAPACITY);

  private final RecordMetadata metadata = new RecordMetadata();

  private final int maxFragmentSize;

  private int eventBufferOffset;
  private int eventLength;
  private int eventCount;

  BufferedStreamWriter(final int maxFragmentSize) {
    reset();

    this.maxFragmentSize = maxFragmentSize;
  }

  MutableDirectBuffer getEventBuffer() {
    return eventBuffer;
  }

  int getEventLength() {
    return eventLength;
  }

  int getEventCount() {
    return eventCount;
  }

  void appendRecord(
      final long key,
      final int sourceIndex,
      final RecordType type,
      final Intent intent,
      final RejectionType rejectionType,
      final String rejectionReason,
      final ValueType valueType,
      final BufferWriter valueWriter) {

    // copy record to buffer
    writeKey(key);
    writeSourceIndex(sourceIndex);

    initMetadata(type, intent, rejectionType, rejectionReason, valueType);
    final var metadataLength = metadata.getLength();
    writeMetadataLength(metadataLength);

    final var valueLength = valueWriter.getLength();
    writeValueLength(valueLength);

    writeMetadata(metadataLength);
    writeValue(valueWriter, valueLength);

    eventLength += metadataLength + valueLength;
    eventCount += 1;
  }

  boolean canWriteAdditionalEvent(final int length) {
    final var count = eventCount + 1;
    final var batchLength = eventLength + length + (count * HEADER_BLOCK_LENGTH);
    return batchLength < maxFragmentSize;
  }

  private void writeKey(long key) {
    if (key < 0) {
      key = LogEntryDescriptor.KEY_NULL_VALUE;
    }
    eventBuffer.putLong(eventBufferOffset, key, Protocol.ENDIANNESS);
    eventBufferOffset += SIZE_OF_LONG;
  }

  private void writeSourceIndex(final int sourceIndex) {
    eventBuffer.putInt(eventBufferOffset, sourceIndex, Protocol.ENDIANNESS);
    eventBufferOffset += SIZE_OF_INT;
  }

  private void initMetadata(
      final RecordType type,
      final Intent intent,
      final RejectionType rejectionType,
      final String rejectionReason,
      final ValueType valueType) {
    metadata.reset();

    metadata
        .recordType(type)
        .valueType(valueType)
        .intent(intent)
        .rejectionType(rejectionType)
        .rejectionReason(rejectionReason);
  }

  private void writeMetadataLength(final int metadataLength) {
    eventBuffer.putInt(eventBufferOffset, metadataLength, Protocol.ENDIANNESS);
    eventBufferOffset += SIZE_OF_INT;
  }

  private void writeValueLength(final int valueLength) {
    eventBuffer.putInt(eventBufferOffset, valueLength, Protocol.ENDIANNESS);
    eventBufferOffset += SIZE_OF_INT;
  }

  private void writeMetadata(final int metadataLength) {
    if (metadataLength > 0) {
      metadata.write(eventBuffer, eventBufferOffset);
      eventBufferOffset += metadataLength;
    }
  }

  private void writeValue(final BufferWriter valueWriter, final int valueLength) {
    valueWriter.write(eventBuffer, eventBufferOffset);
    eventBufferOffset += valueLength;
  }

  void reset() {
    eventBufferOffset = 0;
    eventLength = 0;
    eventCount = 0;
    metadata.reset();
  }
}
