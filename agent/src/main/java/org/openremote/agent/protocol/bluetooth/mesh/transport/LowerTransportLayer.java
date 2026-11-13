/*
 * Copyright 2021, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openremote.agent.protocol.bluetooth.mesh.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import org.openremote.agent.protocol.bluetooth.mesh.MeshManagerApi;
import org.openremote.agent.protocol.bluetooth.mesh.control.BlockAcknowledgementMessage;
import org.openremote.agent.protocol.bluetooth.mesh.opcodes.TransportLayerOpCodes;
import org.openremote.agent.protocol.bluetooth.mesh.utils.ExtendedInvalidCipherTextException;
import org.openremote.agent.protocol.bluetooth.mesh.utils.MeshAddress;
import org.openremote.agent.protocol.bluetooth.mesh.utils.MeshParserUtils;

/**
 * LowerTransportLayer implementation of the mesh network architecture as per the mesh profile
 * specification.
 *
 * <p>This class generates the messages as per the lower transport layer requirements, segmentation
 * and reassembly of mesh messages sent and received, retransmitting messages.
 */
abstract class LowerTransportLayer extends UpperTransportLayer {

  public static final Logger LOG = Logger.getLogger(LowerTransportLayer.class.getName());

  private static final int BLOCK_ACK_TIMER = 150; // Increased from minimum value 150;
  private static final int UNSEGMENTED_HEADER = 0;
  private static final int SEGMENTED_HEADER = 1;
  private static final int UNSEGMENTED_MESSAGE_HEADER_LENGTH = 1;
  private static final int SEGMENTED_MESSAGE_HEADER_LENGTH = 4;
  private static final int UNSEGMENTED_ACK_MESSAGE_HEADER_LENGTH = 3;
  private static final long INCOMPLETE_TIMER_DELAY =
      10 * 1000; // According to the spec the incomplete timer must be a minimum of 10 seconds.

  private final Map<Integer, byte[]> segmentedAccessMessageMap = new HashMap<>();
  private final Map<Integer, byte[]> segmentedControlMessageMap = new HashMap<>();
  LowerTransportLayerCallbacks mLowerTransportLayerCallbacks;
  private boolean mSegmentedAccessAcknowledgementTimerStarted;
  private Integer mSegmentedAccessBlockAck;
  private boolean mSegmentedControlAcknowledgementTimerStarted;
  private Integer mSegmentedControlBlockAck;
  private boolean mIncompleteTimerStarted;
  private boolean mBlockAckSent;
  private long mDuration;

  protected abstract Future<?> startTask(Runnable runnable, Long delay);

  protected abstract void stopTask(Future<?> task);

  protected abstract void stopAllTasks();

  private Future<?> incompleteTimerTask = null;

  /** Runnable for incomplete timer */
  private final Runnable mIncompleteTimerRunnable =
      new Runnable() {
        @Override
        public void run() {
          synchronized (LowerTransportLayer.this) {
            mLowerTransportLayerCallbacks.onIncompleteTimerExpired();
            // Reset the incomplete timer flag once it expires
            mIncompleteTimerStarted = false;
          }
        }
      };

  /**
   * Sets the lower transport layer callbacks
   *
   * @param callbacks {@link LowerTransportLayerCallbacks} callbacks
   */
  abstract void setLowerTransportLayerCallbacks(final LowerTransportLayerCallbacks callbacks);

  /**
   * Creates the network layer pdu
   *
   * @param message message with underlying data
   * @return Complete pdu message that is ready to be sent
   */
  protected abstract Message createNetworkLayerPDU(final Message message);

  @Override
  synchronized void createMeshMessage(final Message message) {
    super.createMeshMessage(message);
    if (message instanceof AccessMessage) {
      createLowerTransportAccessPDU((AccessMessage) message);
    } else {
      createLowerTransportControlPDU((ControlMessage) message);
    }
  }

  @Override
  synchronized void createVendorMeshMessage(final Message message) {
    if (message instanceof AccessMessage) {
      super.createVendorMeshMessage(message);
      createLowerTransportAccessPDU((AccessMessage) message);
    } else {
      createLowerTransportControlPDU((ControlMessage) message);
    }
  }

  @Override
  public final synchronized void createLowerTransportAccessPDU(final AccessMessage message) {
    final byte[] upperTransportPDU = message.getUpperTransportPdu();
    final Map<Integer, byte[]> lowerTransportAccessPduMap;
    if (upperTransportPDU.length <= MAX_SEGMENTED_ACCESS_PAYLOAD_LENGTH) {
      message.setSegmented(false);
      final byte[] lowerTransportPDU = createUnsegmentedAccessMessage(message);
      lowerTransportAccessPduMap = new HashMap<>();
      lowerTransportAccessPduMap.put(0, lowerTransportPDU);
    } else {
      message.setSegmented(true);
      lowerTransportAccessPduMap = createSegmentedAccessMessage(message);
    }

    message.setLowerTransportAccessPdu(lowerTransportAccessPduMap);
  }

  @Override
  public final synchronized void createLowerTransportControlPDU(final ControlMessage message) {
    switch (message.getPduType()) {
      case MeshManagerApi.PDU_TYPE_PROXY_CONFIGURATION:
        final Map<Integer, byte[]> lowerTransportControlPduArray = new HashMap<>();
        lowerTransportControlPduArray.put(0, message.getTransportControlPdu());
        message.setLowerTransportControlPdu(lowerTransportControlPduArray);
        break;
      case MeshManagerApi.PDU_TYPE_NETWORK:
        final byte[] transportControlPdu = message.getTransportControlPdu();
        if (transportControlPdu.length <= MAX_UNSEGMENTED_CONTROL_PAYLOAD_LENGTH) {
          LOG.info("Creating unsegmented transport control");
          createUnsegmentedControlMessage(message);
        } else {
          LOG.info("Creating segmented transport control");
          createSegmentedControlMessage(message);
        }
    }
  }

  @Override
  final synchronized void reassembleLowerTransportAccessPDU(final AccessMessage accessMessage) {
    final Map<Integer, byte[]> lowerTransportAccessPdu =
        removeLowerTransportAccessMessageHeader(accessMessage);
    final byte[] upperTransportPdu =
        MeshParserUtils.concatenateSegmentedMessages(lowerTransportAccessPdu);
    accessMessage.setUpperTransportPdu(upperTransportPdu);
  }

  @Override
  final synchronized void reassembleLowerTransportControlPDU(final ControlMessage controlMessage) {
    final Map<Integer, byte[]> lowerTransportPdu =
        removeLowerTransportControlMessageHeader(controlMessage);
    final byte[] lowerTransportControlPdu =
        MeshParserUtils.concatenateSegmentedMessages(lowerTransportPdu);
    controlMessage.setTransportControlPdu(lowerTransportControlPdu);
  }

  /**
   * Removes the transport header of the access message.
   *
   * @param message access message received.
   * @return map containing the messages.
   */
  private Map<Integer, byte[]> removeLowerTransportAccessMessageHeader(
      final AccessMessage message) {
    final Map<Integer, byte[]> messages = message.getLowerTransportAccessPdu();
    if (message.isSegmented()) {
      /*
      for (int i = 0; i < messages.size(); i++) {
          final byte[] data = messages.get(i);
          final int length = data.length - SEGMENTED_MESSAGE_HEADER_LENGTH; //header size of unsegmented messages is 4;
          messages.put(i, removeHeader(data, 4, length));
      }
      */
      for (Integer index : messages.keySet()) {
        final byte[] data = messages.get(index);
        final int length =
            data.length
                - SEGMENTED_MESSAGE_HEADER_LENGTH; // header size of unsegmented messages is 4;
        messages.put(index, removeHeader(data, 4, length));
      }
    } else {
      /*
      final byte[] data = messages.get(0);
      final int length = data.length - UNSEGMENTED_MESSAGE_HEADER_LENGTH; //header size of unsegmented messages is 1;
      messages.put(0, removeHeader(data, 1, length));
       */
      Integer index = (Integer) messages.keySet().toArray()[0];
      final byte[] data = messages.get(index);
      final int length =
          data.length
              - UNSEGMENTED_MESSAGE_HEADER_LENGTH; // header size of unsegmented messages is 1;
      messages.put(index, removeHeader(data, 1, length));
    }
    return messages;
  }

  /**
   * Removes the transport header of the control message.
   *
   * @param message control message.
   * @return map containing the messages.
   */
  private Map<Integer, byte[]> removeLowerTransportControlMessageHeader(
      final ControlMessage message) {
    final Map<Integer, byte[]> messages = message.getLowerTransportControlPdu();
    if (messages.size() > 1) {
      /*
      for (int i = 0; i < messages.size(); i++) {
          final byte[] data = messages.get(i);
          final int length = data.length - SEGMENTED_MESSAGE_HEADER_LENGTH; //header size of unsegmented messages is 4;
          messages.put(i, removeHeader(data, 4, length));
      }
      */
      for (Integer index : messages.keySet()) {
        final byte[] data = messages.get(index);
        final int length =
            data.length
                - SEGMENTED_MESSAGE_HEADER_LENGTH; // header size of unsegmented messages is 4;
        messages.put(index, removeHeader(data, 4, length));
      }
    } else {
      final int opCode = message.getOpCode();
      final byte[] data;
      final int length;
      if (opCode == TransportLayerOpCodes.SAR_ACK_OPCODE) {
        // data = messages.get(0);
        Integer index = (Integer) messages.keySet().toArray()[0];
        data = messages.get(index);
        length = data.length - UNSEGMENTED_ACK_MESSAGE_HEADER_LENGTH; // header size of unsegmented
        // acknowledgement messages is 3;
        // messages.put(0, removeHeader(data, UNSEGMENTED_ACK_MESSAGE_HEADER_LENGTH, length));
        messages.put(index, removeHeader(data, UNSEGMENTED_ACK_MESSAGE_HEADER_LENGTH, length));
      } else {
        // data = messages.get(0);
        Integer index = (Integer) messages.keySet().toArray()[0];
        data = messages.get(index);
        length =
            data.length
                - UNSEGMENTED_MESSAGE_HEADER_LENGTH; // header size of unsegmented messages is 1;
        // messages.put(0, removeHeader(data, UNSEGMENTED_MESSAGE_HEADER_LENGTH, length));
        messages.put(index, removeHeader(data, UNSEGMENTED_MESSAGE_HEADER_LENGTH, length));
      }
    }
    return messages;
  }

  /**
   * Removes the header from a given array.
   *
   * @param data message.
   * @param offset header offset.
   * @param length header length.
   * @return an array without the header.
   */
  private byte[] removeHeader(final byte[] data, final int offset, final int length) {
    final ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
    buffer.put(data, offset, length);
    return buffer.array();
  }

  /**
   * Creates an unsegmented access message.
   *
   * @param message access message.
   * @return Unsegmented access message.
   */
  private byte[] createUnsegmentedAccessMessage(final AccessMessage message) {
    final byte[] encryptedUpperTransportPDU = message.getUpperTransportPdu();
    final int seg = message.isSegmented() ? 1 : 0;
    final int akfAid = ((message.getAkf() << 6) | message.getAid());
    final byte header = (byte) (((seg << 7) | akfAid));
    final ByteBuffer lowerTransportBuffer =
        ByteBuffer.allocate(1 + encryptedUpperTransportPDU.length).order(ByteOrder.BIG_ENDIAN);
    lowerTransportBuffer.put(header);
    lowerTransportBuffer.put(encryptedUpperTransportPDU);
    final byte[] lowerTransportPDU = lowerTransportBuffer.array();
    LOG.info(
        "Unsegmented Lower transport access PDU "
            + MeshParserUtils.bytesToHex(lowerTransportPDU, false));
    return lowerTransportPDU;
  }

  /**
   * Creates a segmented access message.
   *
   * @param message access message.
   * @return Segmented access message.
   */
  private Map<Integer, byte[]> createSegmentedAccessMessage(final AccessMessage message) {
    final byte[] encryptedUpperTransportPDU = message.getUpperTransportPdu();
    final int akfAid = ((message.getAkf() << 6) | message.getAid());
    final int aszmic = message.getAszmic();
    final byte[] sequenceNumber = message.getSequenceNumber();
    int seqZero = MeshParserUtils.calculateSeqZero(sequenceNumber);

    final int numberOfSegments =
        (encryptedUpperTransportPDU.length + (MAX_SEGMENTED_ACCESS_PAYLOAD_LENGTH - 1))
            / MAX_SEGMENTED_ACCESS_PAYLOAD_LENGTH;
    final int segN = numberOfSegments - 1; // Zero based segN
    final Map<Integer, byte[]> lowerTransportPduMap = new HashMap<>();
    int offset = 0;
    int length;
    for (int segO = 0; segO < numberOfSegments; segO++) {
      // Here we calculate the size of the segments based on the offset and the maximum payload of a
      // segment access message
      length =
          Math.min(encryptedUpperTransportPDU.length - offset, MAX_SEGMENTED_ACCESS_PAYLOAD_LENGTH);
      final ByteBuffer lowerTransportBuffer =
          ByteBuffer.allocate(SEGMENTED_MESSAGE_HEADER_LENGTH + length).order(ByteOrder.BIG_ENDIAN);
      lowerTransportBuffer.put((byte) ((SEGMENTED_HEADER << 7) | akfAid));
      lowerTransportBuffer.put((byte) ((aszmic << 7) | ((seqZero >> 6) & 0x7F)));
      lowerTransportBuffer.put((byte) (((seqZero << 2) & 0xFC) | ((segO >> 3) & 0x03)));
      lowerTransportBuffer.put((byte) (((segO << 5) & 0xE0) | ((segN) & 0x1F)));
      lowerTransportBuffer.put(encryptedUpperTransportPDU, offset, length);
      offset += length;

      final byte[] lowerTransportPDU = lowerTransportBuffer.array();
      LOG.info(
          "Segmented Lower transport access PDU: "
              + MeshParserUtils.bytesToHex(lowerTransportPDU, false)
              + " "
              + segO
              + " of "
              + numberOfSegments);
      lowerTransportPduMap.put(segO, lowerTransportPDU);
    }
    return lowerTransportPduMap;
  }

  /**
   * Creates an unsegmented control.
   *
   * @param message control message.
   */
  private void createUnsegmentedControlMessage(final ControlMessage message) {
    int pduLength;
    final ByteBuffer lowerTransportBuffer;
    message.setSegmented(false);
    final int opCode = message.getOpCode();
    final byte[] parameters = message.getParameters();
    final byte[] upperTransportControlPDU = message.getTransportControlPdu();
    final int header = (byte) ((UNSEGMENTED_HEADER << 7) | opCode);
    if (parameters != null) {
      pduLength =
          UNSEGMENTED_MESSAGE_HEADER_LENGTH + parameters.length + upperTransportControlPDU.length;
      lowerTransportBuffer = ByteBuffer.allocate(pduLength).order(ByteOrder.BIG_ENDIAN);
      lowerTransportBuffer.put((byte) header);
      lowerTransportBuffer.put(parameters);
    } else {
      pduLength = UNSEGMENTED_MESSAGE_HEADER_LENGTH + upperTransportControlPDU.length;
      lowerTransportBuffer = ByteBuffer.allocate(pduLength).order(ByteOrder.BIG_ENDIAN);
      lowerTransportBuffer.put((byte) header);
    }

    lowerTransportBuffer.put(upperTransportControlPDU);
    final byte[] lowerTransportPDU = lowerTransportBuffer.array();
    LOG.info(
        "Unsegmented Lower transport control PDU "
            + MeshParserUtils.bytesToHex(lowerTransportPDU, false));
    final Map<Integer, byte[]> lowerTransportControlPduMap = new HashMap<>();
    lowerTransportControlPduMap.put(0, lowerTransportPDU);
    message.setLowerTransportControlPdu(lowerTransportControlPduMap);
  }

  /**
   * Creates a segmented control message.
   *
   * @param controlMessage control message to be sent.
   */
  private void createSegmentedControlMessage(final ControlMessage controlMessage) {
    controlMessage.setSegmented(false);
    final byte[] encryptedUpperTransportControlPDU = controlMessage.getTransportControlPdu();
    final int opCode = controlMessage.getOpCode();
    final int rfu = 0;
    final byte[] sequenceNumber = controlMessage.getSequenceNumber();
    final int seqZero = MeshParserUtils.calculateSeqZero(sequenceNumber);

    final int numberOfSegments =
        (encryptedUpperTransportControlPDU.length + (MAX_SEGMENTED_CONTROL_PAYLOAD_LENGTH - 1))
            / MAX_SEGMENTED_CONTROL_PAYLOAD_LENGTH;
    final int segN = numberOfSegments - 1; // Zero based segN
    final Map<Integer, byte[]> lowerTransportControlPduMap = new HashMap<>();
    int offset = 0;
    int length;
    for (int segO = 0; segO < numberOfSegments; segO++) {
      // Here we calculate the size of the segments based on the offset and the maximum payload of a
      // segment access message
      length =
          Math.min(
              encryptedUpperTransportControlPDU.length - offset,
              MAX_SEGMENTED_CONTROL_PAYLOAD_LENGTH);
      final ByteBuffer lowerTransportBuffer =
          ByteBuffer.allocate(SEGMENTED_MESSAGE_HEADER_LENGTH + length).order(ByteOrder.BIG_ENDIAN);
      lowerTransportBuffer.put((byte) ((SEGMENTED_HEADER << 7) | opCode));
      lowerTransportBuffer.put((byte) ((rfu << 7) | ((seqZero >> 6) & 0x7F)));
      lowerTransportBuffer.put((byte) (((seqZero << 2) & 0xFC) | ((segO >> 3) & 0x03)));
      lowerTransportBuffer.put((byte) (((segO << 5) & 0xE0) | (segN & 0x1F)));
      lowerTransportBuffer.put(encryptedUpperTransportControlPDU, offset, length);
      offset += length;

      final byte[] lowerTransportPDU = lowerTransportBuffer.array();
      LOG.info(
          "Segmented Lower transport access PDU: "
              + MeshParserUtils.bytesToHex(lowerTransportPDU, false)
              + " "
              + segO
              + " of "
              + numberOfSegments);
      lowerTransportControlPduMap.put(segO, lowerTransportPDU);
    }
    controlMessage.setLowerTransportControlPdu(lowerTransportControlPduMap);
  }

  /**
   * Checks if the received message is a segmented message
   *
   * @param lowerTransportHeader header for the lower transport pdu
   * @return true if segmented and false if not
   */
  /*package*/
  final boolean isSegmentedMessage(final byte lowerTransportHeader) {
    return ((lowerTransportHeader >> 7) & 0x01) == 1;
  }

  /**
   * Parses a unsegmented lower transport access pdu
   *
   * @param pdu The complete pdu was received from the node. This is already de-obfuscated and
   *     decrypted at network layer.
   * @param ivIndex IV Index of the received pdu
   * @param sequenceNumber Sequence number of the message.
   */
  /*package*/
  final synchronized AccessMessage parseUnsegmentedAccessLowerTransportPDU(
      final byte[] pdu, final int ivIndex, final byte[] sequenceNumber) {
    AccessMessage message = null;
    final byte header = pdu[10]; // Lower transport pdu starts here
    final int seg = (header >> 7) & 0x01;
    final int akf = (header >> 6) & 0x01;
    final int aid = header & 0x3F;
    if (seg == 0) { // Unsegmented message
      LOG.info("IV Index of received message: " + ivIndex);
      final int seqAuth = (ivIndex << 24) | MeshParserUtils.convert24BitsToInt(sequenceNumber);
      final byte[] src = MeshParserUtils.getSrcAddress(pdu);
      final int srcAdd = MeshParserUtils.unsignedBytesToInt(src[1], src[0]);
      LOG.info("SeqAuth: " + seqAuth);
      if (!isValidSeqAuth(seqAuth, srcAdd)) {
        return null;
      }
      mMeshNode.setSeqAuth(srcAdd, seqAuth);
      mMeshNode.setSequenceNumber(MeshParserUtils.convert24BitsToInt(sequenceNumber));
      message = new AccessMessage();
      if (akf == 0) { // device key was used to encrypt
        final int lowerTransportPduLength = pdu.length - 10;
        final ByteBuffer lowerTransportBuffer =
            ByteBuffer.allocate(lowerTransportPduLength).order(ByteOrder.BIG_ENDIAN);
        lowerTransportBuffer.put(pdu, 10, lowerTransportPduLength);
        final byte[] lowerTransportPDU = lowerTransportBuffer.array();
        final Map<Integer, byte[]> messages = new HashMap<>();
        messages.put(0, lowerTransportPDU);
        message.setSegmented(false);
        message.setAszmic(0); // aszmic is always 0 for unsegmented access messages
        message.setAkf(akf);
        message.setAid(aid);
        message.setLowerTransportAccessPdu(messages);
      } else {
        final int lowerTransportPduLength = pdu.length - 10;
        final ByteBuffer lowerTransportBuffer =
            ByteBuffer.allocate(lowerTransportPduLength).order(ByteOrder.BIG_ENDIAN);
        lowerTransportBuffer.put(pdu, 10, lowerTransportPduLength);
        final byte[] lowerTransportPDU = lowerTransportBuffer.array();
        final Map<Integer, byte[]> messages = new HashMap<>();
        messages.put(0, lowerTransportPDU);
        message.setSegmented(false);
        message.setAszmic(0); // aszmic is always 0 for unsegmented access messages
        message.setAkf(akf);
        message.setAid(aid);
        message.setLowerTransportAccessPdu(messages);
      }
    }
    return message;
  }

  /**
   * Parses a segmented lower transport access pdu.
   *
   * @param ttl TTL of the acknowledgement
   * @param pdu The complete pdu was received from the node. This is already de-obfuscated and
   *     decrypted at network layer.
   * @param ivIndex Current IV Index of the network
   * @param sequenceNumber Sequence number
   */
  /*package*/
  final synchronized AccessMessage parseSegmentedAccessLowerTransportPDU(
      final int ttl, final byte[] pdu, final int ivIndex, final byte[] sequenceNumber) {
    final byte header = pdu[10]; // Lower transport pdu starts here
    final int akf = (header >> 6) & 0x01;
    final int aid = header & 0x3F;

    final int szmic = (pdu[11] >> 7) & 0x01;
    final int seqZero = ((pdu[11] & 0x7F) << 6) | ((pdu[12] & 0xFC) >> 2);
    final int segO = ((pdu[12] & 0x03) << 3) | ((pdu[13] & 0xE0) >> 5);
    final int segN = ((pdu[13]) & 0x1F);

    final byte[] src = MeshParserUtils.getSrcAddress(pdu);
    final byte[] dst = MeshParserUtils.getDstAddress(pdu);

    final int blockAckSrc =
        MeshParserUtils.unsignedBytesToInt(
            dst[1], dst[0]); // Destination of the received packet would be the source for the ack
    final int blockAckDst =
        MeshParserUtils.unsignedBytesToInt(
            src[1], src[0]); // Source of the received packet would be the destination for the ack

    LOG.info("SEG O: " + segO);
    LOG.info("SEG N: " + segN);

    final int seqNumber =
        getTransportLayerSequenceNumber(
            MeshParserUtils.convert24BitsToInt(sequenceNumber), seqZero);
    final int seqAuth = ivIndex << 24 | seqNumber;
    final Integer lastSeqAuth = mMeshNode.getSeqAuth(blockAckDst);
    if (lastSeqAuth != null) LOG.info("Last SeqAuth value " + lastSeqAuth);

    LOG.info("Current SeqAuth value " + seqAuth);

    final int payloadLength = pdu.length - 10;
    final ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
    payloadBuffer.put(pdu, 10, payloadLength);

    // Check if the current SeqAuth value is greater than the last and if the incomplete timer has
    // not started, start it!
    if ((lastSeqAuth == null || lastSeqAuth < seqAuth)) {
      mMeshNode.setSequenceNumber(seqNumber);
      segmentedAccessMessageMap.clear();
      segmentedAccessMessageMap.put(segO, payloadBuffer.array());
      mMeshNode.setSeqAuth(blockAckDst, seqAuth);

      LOG.info(
          "Starting incomplete timer for src: " + MeshAddress.formatAddress(blockAckDst, false));
      initIncompleteTimer();

      // Start acknowledgement calculation and timer only for messages directed to a unicast
      // address.
      if (MeshAddress.isValidUnicastAddress(dst)) {
        // Calculate the initial block acknowledgement value
        mSegmentedAccessBlockAck =
            BlockAcknowledgementMessage.calculateBlockAcknowledgement(null, segO);
        // Start the block acknowledgement timer irrespective of which segment was received first
        initSegmentedAccessAcknowledgementTimer(seqZero, ttl, blockAckSrc, blockAckDst, segN);
      }
    } else {
      // if the seqauth values are the same and the init complete timer has already started for a
      // received segmented message, we need to restart the incomplete timer
      if (lastSeqAuth == seqAuth) {
        if (mIncompleteTimerStarted) {
          if (segmentedAccessMessageMap.get(segO) == null) {
            segmentedAccessMessageMap.put(segO, payloadBuffer.array());
          }
          final int receivedSegmentedMessageCount = segmentedAccessMessageMap.size();
          LOG.info("Received segment message count: " + receivedSegmentedMessageCount);
          // Add +1 to segN since its zero based
          if (receivedSegmentedMessageCount != (segN + 1)) {
            restartIncompleteTimer();
            LOG.info(
                "Restarting incomplete timer for src: "
                    + MeshAddress.formatAddress(blockAckDst, false));

            // Start acknowledgement calculation and timer only for messages directed to a unicast
            // address.
            // We also have to make sure we restart the acknowledgement timer only if the
            // acknowledgement timer is not active and the incomplete timer is active
            if (MeshAddress.isValidUnicastAddress(dst)
                && !mSegmentedAccessAcknowledgementTimerStarted) {
              mSegmentedAccessBlockAck =
                  BlockAcknowledgementMessage.calculateBlockAcknowledgement(
                      mSegmentedAccessBlockAck, segO);
              LOG.info(
                  "Restarting block acknowledgement timer for src: "
                      + MeshAddress.formatAddress(blockAckDst, false));
              // Start the block acknowledgement timer irrespective of which segment was received
              // first
              initSegmentedAccessAcknowledgementTimer(seqZero, ttl, blockAckSrc, blockAckDst, segN);
            }
          } else {
            if (MeshAddress.isValidUnicastAddress(dst)) {
              mSegmentedAccessBlockAck =
                  BlockAcknowledgementMessage.calculateBlockAcknowledgement(
                      mSegmentedAccessBlockAck, segO);
              handleImmediateBlockAcks(seqZero, ttl, blockAckSrc, blockAckDst, segN);
            } else {
              // We should cancel the incomplete timer since we have received all segments
              cancelIncompleteTimer();
            }

            final AccessMessage accessMessage = new AccessMessage();
            accessMessage.setAszmic(szmic);
            accessMessage.setSequenceNumber(MeshParserUtils.getSequenceNumberBytes(seqNumber));
            accessMessage.setAkf(akf);
            accessMessage.setAid(aid);
            accessMessage.setSegmented(true);
            // final SparseArray<byte[]> segmentedMessages = segmentedAccessMessageMap.clone();
            final Map<Integer, byte[]> segmentedMessages = new HashMap<>();
            for (Integer index : segmentedAccessMessageMap.keySet()) {
              segmentedMessages.put(index, segmentedAccessMessageMap.get(index).clone());
            }
            accessMessage.setLowerTransportAccessPdu(segmentedMessages);
            return accessMessage;
          }
        } else {
          LOG.info(
              "Ignoring message since the incomplete timer has expired and all messages have been received");
        }
      }
    }
    return null;
  }

  /**
   * Send immediate block acknowledgement
   *
   * @param seqZero seqzero of the message
   * @param ttl ttl of the message
   * @param src source address of the message
   * @param dst destination address of the message
   * @param segN total segment count
   */
  private void handleImmediateBlockAcks(
      final int seqZero, final int ttl, final int src, final int dst, final int segN) {
    cancelIncompleteTimer();
    sendBlockAck(seqZero, ttl, src, dst, segN);
  }

  /**
   * Parses a unsegmented lower transport control pdu.
   *
   * @param decryptedProxyPdu The complete pdu was received from the node. This is already
   *     de-obfuscated and decrypted at network layer.
   */
  /*package*/
  final synchronized void parseUnsegmentedControlLowerTransportPDU(
      final ControlMessage controlMessage, final byte[] decryptedProxyPdu)
      throws ExtendedInvalidCipherTextException {

    final Map<Integer, byte[]> unsegmentedMessages = new HashMap<>();
    final int lowerTransportPduLength = decryptedProxyPdu.length - 10;
    final ByteBuffer lowerTransportBuffer =
        ByteBuffer.allocate(lowerTransportPduLength).order(ByteOrder.BIG_ENDIAN);
    lowerTransportBuffer.put(decryptedProxyPdu, 10, lowerTransportPduLength);
    final byte[] lowerTransportPDU = lowerTransportBuffer.array();
    unsegmentedMessages.put(0, lowerTransportPDU);
    final int opCode;
    final int pduType = decryptedProxyPdu[0];
    switch (pduType) {
      case MeshManagerApi.PDU_TYPE_NETWORK:
        final byte header = decryptedProxyPdu[10]; // Lower transport pdu starts here
        opCode = header & 0x7F;
        controlMessage.setPduType(MeshManagerApi.PDU_TYPE_NETWORK); // Set the pdu type here
        controlMessage.setAszmic(0);
        controlMessage.setOpCode(opCode);
        controlMessage.setLowerTransportControlPdu(unsegmentedMessages);
        parseLowerTransportLayerPDU(controlMessage);
        break;
      case MeshManagerApi.PDU_TYPE_PROXY_CONFIGURATION:
        controlMessage.setPduType(MeshManagerApi.PDU_TYPE_PROXY_CONFIGURATION);
        controlMessage.setLowerTransportControlPdu(unsegmentedMessages);
        parseUpperTransportPDU(controlMessage);
        break;
    }
  }

  /**
   * Parses a segmented lower transport control pdu.
   *
   * @param pdu The complete pdu was received from the node. This is already de-obfuscated and
   *     decrypted at network layer.
   */
  /*package*/
  final synchronized ControlMessage parseSegmentedControlLowerTransportPDU(final byte[] pdu) {

    final byte header = pdu[10]; // Lower transport pdu starts here
    final int akf = (header >> 6) & 0x01;
    final int aid = header & 0x3F;

    final int szmic = (pdu[11] >> 7) & 0x01;
    final int seqZero = ((pdu[11] & 0x7F) << 6) | ((pdu[12] & 0xFC) >> 2);
    final int segO = ((pdu[12] & 0x3) << 3) | ((pdu[13] & 0xe0) >> 5);
    final int segN = ((pdu[13]) & 0x1F);

    final int ttl = pdu[2] & 0x7F;
    final byte[] src = MeshParserUtils.getSrcAddress(pdu);
    final byte[] dst = MeshParserUtils.getDstAddress(pdu);

    final int blockAckSrc =
        MeshParserUtils.unsignedBytesToInt(
            dst[1], dst[0]); // Destination of the received packet would be the source for the ack
    final int blockAckDst =
        MeshParserUtils.unsignedBytesToInt(
            src[1], src[0]); // Source of the received packet would be the destination for the ack

    LOG.info("SEG O: " + segO);
    LOG.info("SEG N: " + segN);

    // Start the timer irrespective of which segment was received first
    initSegmentedControlAcknowledgementTimer(seqZero, ttl, blockAckDst, blockAckSrc, segN);
    mSegmentedControlBlockAck =
        BlockAcknowledgementMessage.calculateBlockAcknowledgement(mSegmentedControlBlockAck, segO);
    LOG.info("Block acknowledgement value for " + mSegmentedControlBlockAck + " Seg O " + segO);

    final int payloadLength = pdu.length - 10;

    final ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
    payloadBuffer.put(pdu, 10, payloadLength);
    segmentedControlMessageMap.put(segO, payloadBuffer.array());

    // Check the message count against the zero-based segN;
    final int receivedSegmentedMessageCount = segmentedControlMessageMap.size() - 1;
    if (segN == receivedSegmentedMessageCount) {
      LOG.info("All segments received");
      // Remove the incomplete timer if all segments were received

      // mHandler.removeCallbacks(mIncompleteTimerRunnable);
      stopTask(incompleteTimerTask);
      incompleteTimerTask = null;

      LOG.info("Block ack sent? " + mBlockAckSent);
      if (mDuration > System.currentTimeMillis() && !mBlockAckSent) {
        if (MeshAddress.isValidUnicastAddress(dst)) {

          // mHandler.removeCallbacksAndMessages(null);
          stopAllTasks();
          incompleteTimerTask = null;

          LOG.info(
              "Canceling Scheduled block ack and incomplete timer, sending an immediate block ack");
          sendBlockAck(seqZero, ttl, blockAckSrc, blockAckDst, segN);
          // mBlockAckSent = false;
        }
      }
      final int upperTransportSequenceNumber =
          getTransportLayerSequenceNumber(MeshParserUtils.getSequenceNumberFromPDU(pdu), seqZero);
      final byte[] sequenceNumber =
          MeshParserUtils.getSequenceNumberBytes(upperTransportSequenceNumber);
      final ControlMessage message = new ControlMessage();
      message.setAszmic(szmic);
      message.setSequenceNumber(sequenceNumber);
      message.setAkf(akf);
      message.setAid(aid);
      message.setSegmented(true);
      // final SparseArray<byte[]> segmentedMessages = segmentedControlMessageMap.clone();
      Map<Integer, byte[]> segmentedMessages = new HashMap<>();
      for (Integer index : segmentedControlMessageMap.keySet()) {
        segmentedMessages.put(index, segmentedControlMessageMap.get(index).clone());
      }
      segmentedControlMessageMap.clear();
      message.setLowerTransportControlPdu(segmentedMessages);
      return message;
    }

    return null;
  }

  /** Start incomplete timer for segmented messages. */
  private void initIncompleteTimer() {

    // mHandler.postDelayed(mIncompleteTimerRunnable, INCOMPLETE_TIMER_DELAY);
    incompleteTimerTask = startTask(mIncompleteTimerRunnable, INCOMPLETE_TIMER_DELAY);

    mIncompleteTimerStarted = true;
  }

  /** Restarts the incomplete timer */
  private void restartIncompleteTimer() {
    // Remove the existing incomplete timer
    if (mIncompleteTimerStarted) {

      // mHandler.removeCallbacks(mIncompleteTimerRunnable);
      stopTask(incompleteTimerTask);
      incompleteTimerTask = null;
    }
    // Call init to start the timer again
    initIncompleteTimer();
  }

  /** Cancels an already started the incomplete timer */
  private void cancelIncompleteTimer() {
    // Remove the existing incomplete timer
    mIncompleteTimerStarted = false;

    // mHandler.removeCallbacks(mIncompleteTimerRunnable);
    stopTask(incompleteTimerTask);
    incompleteTimerTask = null;
  }

  /**
   * Start acknowledgement timer for segmented messages.
   *
   * @param seqZero Seqzero of the segmented messages.
   * @param ttl TTL of the segmented messages.
   * @param dst Destination address.
   */
  private void initSegmentedAccessAcknowledgementTimer(
      final int seqZero, final int ttl, final int src, final int dst, final int segN) {
    if (!mSegmentedAccessAcknowledgementTimerStarted) {
      mSegmentedAccessAcknowledgementTimerStarted = true;
      LOG.info("TTL: " + ttl);
      final int duration = (BLOCK_ACK_TIMER + (50 * ttl));
      LOG.info("Duration: " + duration);
      mDuration = System.currentTimeMillis() + duration;

      // mHandler.postDelayed(() -> {
      //    LOG.info("Acknowledgement timer expiring");
      //    sendBlockAck(seqZero, ttl, src, dst, segN);
      // }, duration);

      startTask(
          () -> {
            synchronized (LowerTransportLayer.this) {
              LOG.info("Acknowledgement timer expiring");
              sendBlockAck(seqZero, ttl, src, dst, segN);
            }
          },
          Integer.valueOf(duration).longValue());
    }
  }

  /**
   * Start acknowledgement timer for segmented messages.
   *
   * @param seqZero Seqzero of the segmented messages.
   * @param ttl TTL of the segmented messages.
   * @param src Source address which is the element address
   * @param dst Destination address.
   */
  private void initSegmentedControlAcknowledgementTimer(
      final int seqZero, final int ttl, final int src, final int dst, final int segN) {
    if (!mSegmentedControlAcknowledgementTimerStarted) {
      mSegmentedControlAcknowledgementTimerStarted = true;
      final int duration = BLOCK_ACK_TIMER + (50 * ttl);
      mDuration = System.currentTimeMillis() + duration;
      // mHandler.postDelayed(() -> sendBlockAck(seqZero, ttl, src, dst, segN), duration);

      startTask(
          () -> {
            synchronized (LowerTransportLayer.this) {
              sendBlockAck(seqZero, ttl, src, dst, segN);
            }
          },
          Integer.valueOf(duration).longValue());
    }
  }

  /**
   * Send block acknowledgement
   *
   * @param seqZero Seqzero of the segmented messages.
   * @param ttl TTL of the segmented messages.
   * @param src Source address which is the element address
   * @param dst Destination address.
   */
  private void sendBlockAck(
      final int seqZero, final int ttl, final int src, final int dst, final int segN) {
    final int blockAck = mSegmentedAccessBlockAck;
    if (BlockAcknowledgementMessage.hasAllSegmentsBeenReceived(blockAck, segN)) {
      LOG.info("All segments received canceling incomplete timer");
      cancelIncompleteTimer();
    }

    final byte[] upperTransportControlPdu = createAcknowledgementPayload(seqZero, blockAck);
    LOG.info(
        "Block acknowledgement payload: "
            + MeshParserUtils.bytesToHex(upperTransportControlPdu, false));
    final ControlMessage controlMessage = new ControlMessage();
    controlMessage.setOpCode(TransportLayerOpCodes.SAR_ACK_OPCODE);
    controlMessage.setTransportControlPdu(upperTransportControlPdu);
    controlMessage.setTtl(ttl);
    controlMessage.setPduType(MeshManagerApi.PDU_TYPE_NETWORK);
    controlMessage.setSrc(src);
    controlMessage.setDst(dst);
    controlMessage.setIvIndex(mUpperTransportLayerCallbacks.getIvIndex());
    final int sequenceNumber =
        mUpperTransportLayerCallbacks.getNode(controlMessage.getSrc()).incrementSequenceNumber();
    final byte[] sequenceNum = MeshParserUtils.getSequenceNumberBytes(sequenceNumber);
    controlMessage.setSequenceNumber(sequenceNum);
    mBlockAckSent = true;
    mLowerTransportLayerCallbacks.sendSegmentAcknowledgementMessage(controlMessage);
    mSegmentedAccessAcknowledgementTimerStarted = false;
  }

  /**
   * Creates the acknowledgement parameters.
   *
   * @param seqZero Seqzero of the message.
   * @param blockAcknowledgement Block acknowledgement
   * @return acknowledgement parameters.
   */
  private byte[] createAcknowledgementPayload(final int seqZero, final int blockAcknowledgement) {
    final int obo = 0;
    final int rfu = 0;

    final ByteBuffer buffer = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
    buffer.put((byte) ((obo << 7) | (seqZero >> 6) & 0x7F));
    buffer.put((byte) (((seqZero << 2) & 0xFC) | rfu));
    buffer.putInt(blockAcknowledgement);
    return buffer.array();
  }

  /**
   * Parse transport layer control pdu.
   *
   * @param controlMessage underlying message containing the access pdu.
   */
  private void parseLowerTransportLayerPDU(final ControlMessage controlMessage) {
    // First we reassemble the transport layer message if its a segmented message
    reassembleLowerTransportControlPDU(controlMessage);
    final byte[] transportControlPdu = controlMessage.getTransportControlPdu();
    final int opCode = controlMessage.getOpCode();

    if (opCode == TransportLayerOpCodes.SAR_ACK_OPCODE) {
      final BlockAcknowledgementMessage acknowledgement =
          new BlockAcknowledgementMessage(transportControlPdu);
      controlMessage.setTransportControlMessage(acknowledgement);
    }
  }

  /**
   * Validates Sequence authentication value.
   *
   * @param seqAuth Sequence authentication.
   * @param src Source address.
   */
  private boolean isValidSeqAuth(final int seqAuth, final int src) {
    final Integer lastSeqAuth = mMeshNode.getSeqAuth(src);
    return lastSeqAuth == null || lastSeqAuth < seqAuth;
  }
}
