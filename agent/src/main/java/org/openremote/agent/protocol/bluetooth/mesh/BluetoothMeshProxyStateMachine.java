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
package org.openremote.agent.protocol.bluetooth.mesh;

import static org.openremote.agent.protocol.bluetooth.mesh.BluetoothMeshProxy.*;

import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCommandStatus;
import com.welie.blessed.BluetoothGattCharacteristic;
import com.welie.blessed.BluetoothGattService;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ScanResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.syslog.SyslogCategory;

public class BluetoothMeshProxyStateMachine {

  public static final Logger LOG =
      SyslogCategory.getLogger(
          SyslogCategory.PROTOCOL, BluetoothMeshProxyStateMachine.class.getName());

  public static final int MAX_RETRY_COUNT = 20;
  public static final int CONNECT_TIMEOUT = 60000;
  public static final int SCAN_TIMEOUT = 60000;

  private final BluetoothMeshProxy meshProxy;
  private final BluetoothCentralManager bluetoothCentral;
  private volatile BluetoothPeripheral peripheral;
  private final BluetoothPeripheralCallback peripheralCallback;
  private final ExecutorService executorService;
  private final ScheduledExecutorService scheduledExecutorService;
  private volatile BluetoothMeshProxyRxCallback rxDataCallback;
  private volatile Consumer<ConnectionStatus> statusConsumer;
  private volatile BluetoothMeshProxyConnectCallback connectCallback;
  private volatile BluetoothGattCharacteristic dataInCharacteristic;
  private volatile BluetoothGattCharacteristic dataOutCharacteristic;
  private volatile int retryCount = 0;
  private volatile boolean isInitialConnectionSuccessful = false;

  private final BlockingQueue<SendDataCommand> sendDataQueue = new LinkedBlockingQueue<>();
  private final QueueWorker queueWorker = new QueueWorker(sendDataQueue);
  private volatile ScheduledFuture<?> workerFuture;
  private volatile ScheduledFuture<?> timeoutFuture;
  private final MainThreadManager commandSerializer;

  private final StartState startState = new StartState(this);
  private final RetryToConnectState retryToConnectState = new RetryToConnectState(this);
  private final ConnectingState connectingState = new ConnectingState(this);
  private final ConnectedState connectedState = new ConnectedState(this);
  private final ServicesDiscoveredState servicesDiscoveredState = new ServicesDiscoveredState(this);
  private final ConfigureCharacteristicState configureCharacteristicState =
      new ConfigureCharacteristicState(this);
  private final EndState endState = new EndState(this);
  private final ScanState scanState = new ScanState(this);
  private final DisconnectedState disconnectedState = new DisconnectedState(this);
  private final FailedState failedState = new FailedState(this);
  private volatile State state = startState;

  public BluetoothMeshProxyStateMachine(
      BluetoothMeshProxy proxy,
      BluetoothCentralManager bluetoothCentral,
      ExecutorService executorService,
      ScheduledExecutorService scheduledExecutorService,
      MainThreadManager commandSerializer,
      BluetoothPeripheral peripheral,
      BluetoothPeripheralCallback callback) {
    this.meshProxy = proxy;
    this.bluetoothCentral = bluetoothCentral;
    this.commandSerializer = commandSerializer;
    this.executorService = executorService;
    this.scheduledExecutorService = scheduledExecutorService;
    this.peripheral = peripheral;
    this.peripheralCallback = callback;
  }

  void setState(State state) {
    this.state = state;
  }

  StartState getStartState() {
    return startState;
  }

  RetryToConnectState getRetryToConnectState() {
    return retryToConnectState;
  }

  ConnectingState getConnectingState() {
    return connectingState;
  }

  ConnectedState getConnectedState() {
    return connectedState;
  }

  ServicesDiscoveredState getServicesDiscoveredState() {
    return servicesDiscoveredState;
  }

  ConfigureCharacteristicState getConfigureCharacteristicState() {
    return configureCharacteristicState;
  }

  EndState getEndState() {
    return endState;
  }

  ScanState getScanState() {
    return scanState;
  }

  DisconnectedState getDisconnectedState() {
    return disconnectedState;
  }

  FailedState getFailedState() {
    return failedState;
  }

  public synchronized void setRxDataCallback(BluetoothMeshProxyRxCallback callback) {
    this.rxDataCallback = callback;
  }

  public synchronized void connect(
      Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {
    state.connect(statusConsumer, callback);
  }

  public synchronized void disconnect() {
    state.disconnect();
  }

  public synchronized void sendData(
      int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {
    state.sendData(mtuSize, data, callback);
  }

  public synchronized void onConnectedPeripheral(BluetoothPeripheral peripheral) {
    state.onConnectedPeripheral(peripheral);
  }

  public synchronized void onConnectionFailed(
      BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
    state.onConnectionFailed(peripheral, status);
    state.connect(statusConsumer, connectCallback);
  }

  public synchronized void onDisconnectedPeripheral(
      BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
    state.onDisconnectedPeripheral(peripheral, status);
    state.connect(statusConsumer, connectCallback);
  }

  public synchronized void onServicesDiscovered(
      BluetoothPeripheral peripheral, List<BluetoothGattService> services) {
    state.onServicesDiscovered(peripheral, services);
    state.configureOutCharacteristic();
  }

  public synchronized void onNotificationStateUpdate(
      BluetoothPeripheral peripheral,
      BluetoothGattCharacteristic characteristic,
      BluetoothCommandStatus status) {
    state.onNotificationStateUpdate(peripheral, characteristic, status);
  }

  public synchronized void onCharacteristicUpdate(
      BluetoothPeripheral peripheral,
      byte[] value,
      BluetoothGattCharacteristic characteristic,
      BluetoothCommandStatus status) {
    if (!dataOutCharacteristic.getUuid().equals(characteristic.getUuid())) {
      return;
    }
    if (status == BluetoothCommandStatus.COMMAND_SUCCESS) {
      LOG.info(
          "Received '"
              + value.length
              + "' data bytes from mesh proxy: [data="
              + dataAsHexString(value)
              + "]");
      final BluetoothMeshProxyRxCallback callback = rxDataCallback;
      if (callback != null) {
        executorService.execute(() -> callback.onRxData(value));
      }
    }
  }

  public synchronized void onCharacteristicWrite(
      BluetoothPeripheral peripheral,
      byte[] value,
      BluetoothGattCharacteristic characteristic,
      BluetoothCommandStatus status) {
    if (queueWorker != null) {
      queueWorker.onCharacteristicWrite(peripheral, value, characteristic, status);
    }
  }

  public synchronized void onDiscoveredPeripheral(
      BluetoothPeripheral peripheral, ScanResult scanResult) {
    state.onDiscoveredPeripheral(peripheral, scanResult);
    state.connect(statusConsumer, connectCallback);
  }

  public synchronized boolean isConnected() {
    return state == getEndState();
  }

  synchronized void onTimeout() {
    LOG.info("Timeout fired !");
    state.onTimeout();
  }

  void setStatusConsumer(Consumer<ConnectionStatus> statusConsumer) {
    this.statusConsumer = statusConsumer;
  }

  Consumer<ConnectionStatus> getStatusConsumer() {
    return statusConsumer;
  }

  void setConnectCallback(BluetoothMeshProxyConnectCallback callback) {
    this.connectCallback = callback;
  }

  BluetoothMeshProxyConnectCallback getConnectCallback() {
    return connectCallback;
  }

  void setDataInCharacteristic(BluetoothGattCharacteristic characteristic) {
    this.dataInCharacteristic = characteristic;
  }

  BluetoothGattCharacteristic getDataInCharacteristic() {
    return dataInCharacteristic;
  }

  void setDataOutCharacteristic(BluetoothGattCharacteristic characteristic) {
    this.dataOutCharacteristic = characteristic;
  }

  BluetoothGattCharacteristic getDataOutCharacteristic() {
    return dataOutCharacteristic;
  }

  void startQueueWorker() {
    workerFuture = scheduledExecutorService.schedule(queueWorker, 0, TimeUnit.MILLISECONDS);
  }

  void stopQueueWorker() {
    if (workerFuture != null) {
      workerFuture.cancel(true);
      workerFuture = null;
    }
    sendDataQueue.clear();
  }

  int getRetryCount() {
    return retryCount;
  }

  void setRetryCount(int count) {
    this.retryCount = count;
  }

  int incrementRetryCount() {
    return ++retryCount;
  }

  BluetoothCentralManager getBluetoothCentral() {
    return bluetoothCentral;
  }

  public synchronized BluetoothPeripheral getPeripheral() {
    return peripheral;
  }

  void setPeripheral(BluetoothPeripheral peripheral) {
    this.peripheral = peripheral;
  }

  BluetoothPeripheralCallback getPeripheralCallback() {
    return peripheralCallback;
  }

  void notifyStatusConsumer(ConnectionStatus status) {
    executorService.execute(() -> statusConsumer.accept(status));
  }

  void executeOnMainThread(Runnable runnable) {
    commandSerializer.enqueue(runnable);
  }

  boolean isExpectedPeripheral(BluetoothPeripheral peripheral) {
    if (peripheral != null && this.peripheral != null) {
      return peripheral.getAddress().equalsIgnoreCase(this.peripheral.getAddress());
    } else {
      return false;
    }
  }

  void notifyListeners(boolean isSuccess, ConnectionStatus status) {
    boolean isConnectionLoss = !isSuccess ? isInitialConnectionSuccessful : false;
    executorService.execute(
        () -> connectCallback.onMeshProxyConnected(peripheral, isSuccess, isConnectionLoss));
    executorService.execute(() -> statusConsumer.accept(status));
  }

  void disableOutCharacteristicNotifications() {
    final BluetoothGattCharacteristic out = getDataOutCharacteristic();
    if (out != null) {
      executeOnMainThread(
          () -> {
            LOG.info(
                "Disabling out characteristic notifications: [Name="
                    + getPeripheral().getName()
                    + ", Address="
                    + getPeripheral().getAddress()
                    + "]");
            getPeripheral().setNotify(out, false);
          });
    }
  }

  void cancelConnection() {
    executeOnMainThread(
        () -> {
          LOG.info(
              "Canceling peripheral connection: [Name="
                  + getPeripheral().getName()
                  + ", Address="
                  + getPeripheral().getAddress()
                  + "]");
          getBluetoothCentral().cancelConnection(getPeripheral());
        });
  }

  void scanOn() {
    try {
      Thread.sleep(4000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    executeOnMainThread(
        () -> {
          LOG.info("Scan ON");
          getBluetoothCentral().scanForPeripheralsWithServices(new UUID[] {MESH_PROXY_UUID});
        });
  }

  void scanOff() {
    executeOnMainThread(
        () -> {
          LOG.info("Scan OFF");
          getBluetoothCentral().stopScan();
        });
  }

  void startTimeout(int timeout) {
    timeoutFuture =
        scheduledExecutorService.schedule(
            () -> {
              onTimeout();
            },
            timeout,
            TimeUnit.MILLISECONDS);
  }

  void stopTimeout() {
    if (timeoutFuture != null) {
      if ((!timeoutFuture.isCancelled()) && (!timeoutFuture.isDone())) {
        timeoutFuture.cancel(true);
      }
      timeoutFuture = null;
    }
  }

  String dataAsHexString(byte[] data) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < data.length; i++) {
      builder.append(String.format("0x%02X%s", data[i] & 0xFF, i == (data.length - 1) ? "" : ", "));
    }
    return builder.toString();
  }

  // Nested Classes -----------------------------------------------------------------------------

  private interface State {
    void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback);

    void disconnect();

    void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback);

    void onConnectedPeripheral(BluetoothPeripheral peripheral);

    void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status);

    void onDisconnectedPeripheral(BluetoothPeripheral peripheral, BluetoothCommandStatus status);

    void onServicesDiscovered(BluetoothPeripheral peripheral, List<BluetoothGattService> services);

    void configureOutCharacteristic();

    void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status);

    void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status);

    void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status);

    void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult);

    void onTimeout();
  }

  private class StartState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public StartState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {
      setStatusConsumer(statusConsumer);
      setConnectCallback(callback);

      startQueueWorker();
      setRetryCount(1);
      setDataInCharacteristic(null);
      setDataOutCharacteristic(null);

      startTimeout(CONNECT_TIMEOUT);
      setState(getConnectingState());

      notifyStatusConsumer(ConnectionStatus.CONNECTING);

      executeOnMainThread(
          () -> {
            try {
              Thread.sleep(4000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            LOG.info(
                "Initially connecting to mesh proxy: [Name="
                    + getPeripheral().getName()
                    + ", Address="
                    + getPeripheral().getAddress()
                    + "]");
            getBluetoothCentral().connectPeripheral(getPeripheral(), getPeripheralCallback());
          });
    }

    @Override
    public void disconnect() {}

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class RetryToConnectState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public RetryToConnectState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {
      // initQueueWorker();
      incrementRetryCount();
      setDataInCharacteristic(null);
      setDataOutCharacteristic(null);

      stopTimeout();
      startTimeout(CONNECT_TIMEOUT);
      setState(getConnectingState());

      notifyStatusConsumer(ConnectionStatus.CONNECTING);

      executeOnMainThread(
          () -> {
            try {
              Thread.sleep(4000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            LOG.info(
                "Reconnecting to mesh proxy: [RetryCount="
                    + getRetryCount()
                    + ", Name="
                    + getPeripheral().getName()
                    + ", Address="
                    + getPeripheral().getAddress()
                    + "]");
            getBluetoothCentral().connectPeripheral(getPeripheral(), getPeripheralCallback());
          });
    }

    @Override
    public void disconnect() {
      setState(getDisconnectedState());
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class ConnectingState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public ConnectingState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {
      stopTimeout();
      setState(getDisconnectedState());
      cancelConnection();
      notifyStatusConsumer(ConnectionStatus.DISCONNECTED);
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
      if (isExpectedPeripheral(peripheral)) {
        stopTimeout();
        setState(getConnectedState());
      }
    }

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
      if (isExpectedPeripheral(peripheral)) {
        if (getRetryCount() < MAX_RETRY_COUNT) {
          stopTimeout();
          startTimeout(SCAN_TIMEOUT);
          setState(getScanState());
          scanOn();
        } else {
          notifyListeners(false, ConnectionStatus.ERROR);
          stopTimeout();
          setState(getFailedState());
          stopQueueWorker();
        }
      }
    }

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {
      LOG.warning("ConnectingState::onTimeout");
      notifyListeners(false, ConnectionStatus.ERROR);
      setState(getFailedState());
      stopQueueWorker();
      // cancelConnection();
      // startTimeout(SCAN_TIMEOUT);
      // setState(getScanState());
      // scanOn();
    }
  }

  private class ConnectedState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public ConnectedState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {
      setState(getDisconnectedState());
      cancelConnection();
      notifyStatusConsumer(ConnectionStatus.DISCONNECTED);
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
      if (isExpectedPeripheral(peripheral)) {
        stopTimeout();
        startTimeout(SCAN_TIMEOUT);
        setState(getScanState());
        scanOn();
      }
    }

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {
      if (isExpectedPeripheral(peripheral)) {
        final BluetoothGattCharacteristic in =
            peripheral.getCharacteristic(MESH_PROXY_UUID, MESH_PROXY_DATA_IN);
        final BluetoothGattCharacteristic out =
            peripheral.getCharacteristic(MESH_PROXY_UUID, MESH_PROXY_DATA_OUT);
        if (in != null && out != null) {
          setDataInCharacteristic(in);
          setDataOutCharacteristic(out);
          setState(getServicesDiscoveredState());
        } else {
          notifyListeners(false, ConnectionStatus.ERROR);
          setState(getFailedState());
          stopQueueWorker();
        }
      }
    }

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class ServicesDiscoveredState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public ServicesDiscoveredState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {
      setState(getDisconnectedState());
      cancelConnection();
      notifyStatusConsumer(ConnectionStatus.DISCONNECTED);
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
      if (isExpectedPeripheral(peripheral)) {
        stopTimeout();
        startTimeout(SCAN_TIMEOUT);
        setState(getScanState());
        scanOn();
      }
    }

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {
      final BluetoothGattCharacteristic out = getDataOutCharacteristic();
      executeOnMainThread(
          () -> {
            LOG.info(
                "Enabling out characteristic notifications: [Name="
                    + getPeripheral().getName()
                    + ", Address="
                    + getPeripheral().getAddress()
                    + "]");
            getPeripheral().setNotify(out, true);
          });
      setState(getConfigureCharacteristicState());
    }

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {
      LOG.info("ConfigureCharacteristicState::onNotificationStateUpdate: status=" + status);
    }

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class ConfigureCharacteristicState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public ConfigureCharacteristicState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {
      setState(getDisconnectedState());
      disableOutCharacteristicNotifications();
      cancelConnection();
      notifyStatusConsumer(ConnectionStatus.DISCONNECTED);
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
      if (isExpectedPeripheral(peripheral)) {
        stopTimeout();
        startTimeout(SCAN_TIMEOUT);
        setState(getScanState());
        scanOn();
      }
    }

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {
      if (isExpectedPeripheral(peripheral)
          && getDataOutCharacteristic().getUuid().equals(characteristic.getUuid())) {
        if (status == BluetoothCommandStatus.COMMAND_SUCCESS) {
          isInitialConnectionSuccessful = true;
          setRetryCount(0);
          notifyListeners(true, ConnectionStatus.CONNECTED);
          setState(getEndState());
        } else {
          notifyListeners(false, ConnectionStatus.ERROR);
          setState(getFailedState());
          stopQueueWorker();
        }
      }
    }

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class EndState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public EndState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {
      setState(getDisconnectedState());
      disableOutCharacteristicNotifications();
      executeOnMainThread(
          () -> {
            try {
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      cancelConnection();
      // Add delay between disconnection and next reconnection
      executeOnMainThread(
          () -> {
            try {
              Thread.sleep(10000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      notifyStatusConsumer(ConnectionStatus.DISCONNECTED);
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {
      SendDataCommand cmd = null;
      if (data.length > mtuSize) {
        cmd =
            new SendMultiDataSegmentCommand(
                meshProxy,
                commandSerializer,
                mtuSize,
                executorService,
                dataInCharacteristic,
                data,
                callback);
      } else {
        cmd =
            new SendSingleDataSegmentCommand(
                meshProxy,
                commandSerializer,
                executorService,
                dataInCharacteristic,
                data,
                callback);
      }
      try {
        sendDataQueue.put(cmd);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {
      if (isExpectedPeripheral(peripheral)) {
        stopTimeout();
        startTimeout(SCAN_TIMEOUT);
        setState(getScanState());
        scanOn();
        executorService.execute(() -> statusConsumer.accept(ConnectionStatus.DISCONNECTED));
      }
    }

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class ScanState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public ScanState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {
      stopTimeout();
      scanOff();
      setState(getDisconnectedState());
      notifyStatusConsumer(ConnectionStatus.DISCONNECTED);
      stopQueueWorker();
    }

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
      BluetoothPeripheral oldPeripheral = getPeripheral();
      BluetoothPeripheral newPeripheral = peripheral;
      if (oldPeripheral.getAddress().equals(newPeripheral.getAddress())) {
        LOG.info(
            "Scanned Bluetooth Mesh proxy after connection loss: [Name='"
                + peripheral.getName()
                + "', address='"
                + peripheral.getAddress()
                + "']");
        setPeripheral(newPeripheral);
        stopTimeout();
        scanOff();
        setState(getRetryToConnectState());
        executorService.execute(() -> statusConsumer.accept(ConnectionStatus.CONNECTING));
      }
    }

    @Override
    public void onTimeout() {
      LOG.warning("ScanState::onTimeout");
      scanOff();
      notifyListeners(false, ConnectionStatus.ERROR);
      setState(getFailedState());
      stopQueueWorker();
    }
  }

  private class FailedState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public FailedState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {}

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class DisconnectedState implements State {

    private final BluetoothMeshProxyStateMachine stateMachine;

    public DisconnectedState(BluetoothMeshProxyStateMachine machine) {
      this.stateMachine = machine;
    }

    @Override
    public void connect(
        Consumer<ConnectionStatus> statusConsumer, BluetoothMeshProxyConnectCallback callback) {}

    @Override
    public void disconnect() {}

    @Override
    public void sendData(int mtuSize, byte[] data, BluetoothMeshProxySendDataCallback callback) {}

    @Override
    public void onConnectedPeripheral(BluetoothPeripheral peripheral) {}

    @Override
    public void onConnectionFailed(BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onDisconnectedPeripheral(
        BluetoothPeripheral peripheral, BluetoothCommandStatus status) {}

    @Override
    public void onServicesDiscovered(
        BluetoothPeripheral peripheral, List<BluetoothGattService> services) {}

    @Override
    public void configureOutCharacteristic() {}

    @Override
    public void onNotificationStateUpdate(
        BluetoothPeripheral peripheral,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicUpdate(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {}

    @Override
    public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {}

    @Override
    public void onTimeout() {}
  }

  private class QueueWorker extends BluetoothPeripheralCallback implements Runnable {

    private final BlockingQueue<SendDataCommand> queue;
    private SendDataCommand pendingCommand = null;

    public QueueWorker(BlockingQueue<SendDataCommand> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          SendDataCommand command = queue.take();
          synchronized (QueueWorker.this) {
            pendingCommand = command;
          }
          // Note: send data is blocking
          command.sendData();
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    // Implements BluetoothPeripheralCallback -------------------------------------------------

    @Override
    public synchronized void onCharacteristicWrite(
        BluetoothPeripheral peripheral,
        byte[] value,
        BluetoothGattCharacteristic characteristic,
        BluetoothCommandStatus status) {
      if (pendingCommand != null) {
        pendingCommand.onCharacteristicWrite(peripheral, value, characteristic, status);
      }
    }
  }
}
