package com.example.carrinhobluetooth; // ATENÇÃO: Altere 'com.example.carrinhobluetooth' para o pacote do seu projeto

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog; // Para exibir mensagens de alerta (opcional)
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CarrinhoBluetooth"; // Tag para logs no Logcat
    // UUID (Universally Unique Identifier) padrão para o Serial Port Profile (SPP)
    // Este UUID é padrão para comunicação serial Bluetooth e não deve ser alterado.
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Nome do seu dispositivo ESP32 configurado no firmware (BluetoothSerial.begin("NOME_DO_SEU_ESP32"))
    // ESTE NOME DEVE SER EXATAMENTE O MESMO DO SEU ESP32!
    private static final String ESP32_DEVICE_NAME = "Monster_High";

    private BluetoothAdapter bluetoothAdapter = null; // Adaptador Bluetooth do Android
    private BluetoothSocket btSocket = null;           // Socket para a conexão Bluetooth
    private OutputStream outputStream = null;          // Stream para enviar dados para o ESP32

    // Elementos da Interface do Usuário (UI)
    private Button btnConnect, btnStop;
    private ImageButton btnForward, btnBackward, btnLeft, btnRight;
    private ImageView bluetoothStatusImageView;

    // Launcher para solicitar permissões de Bluetooth em tempo de execução (para Android 6.0+)
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Define o layout da atividade

        // Inicializa os elementos da UI a partir do layout XML
        btnConnect = findViewById(R.id.btnConnect);
        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnStop = findViewById(R.id.btnStop);
        bluetoothStatusImageView = findViewById(R.id.imageView3);

        // Desabilita os botões de controle de movimento até que o Bluetooth esteja conectado
        setControlButtonsEnabled(false);
        bluetoothStatusImageView.setVisibility(View.GONE);

        // Obtém o adaptador Bluetooth padrão do dispositivo.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            // Se o dispositivo não suporta Bluetooth, exibe uma mensagem e fecha o app.
            Toast.makeText(this, "Seu dispositivo não suporta Bluetooth", Toast.LENGTH_LONG).show();
            finish();
        }

        // Configura o launcher para lidar com o resultado da solicitação de permissões.
        requestPermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    // Verifica se todas as permissões necessárias foram concedidas.
                    boolean allGranted = true;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        // Para Android 12 (API 31) e superior, precisamos de BLUETOOTH_CONNECT e BLUETOOTH_SCAN,
                        // além de ACCESS_FINE_LOCATION para escaneamento.
                        if (!permissions.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false) ||
                                !permissions.getOrDefault(Manifest.permission.BLUETOOTH_SCAN, false) ||
                                !permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
                            allGranted = false;
                        }
                    } else {
                        // Para Android 11 (API 30) e inferior, precisamos de BLUETOOTH, BLUETOOTH_ADMIN e
                        // ACCESS_FINE_LOCATION para escaneamento (API 23+).
                        if (!permissions.getOrDefault(Manifest.permission.BLUETOOTH, false) ||
                                !permissions.getOrDefault(Manifest.permission.BLUETOOTH_ADMIN, false) ||
                                !permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
                            allGranted = false;
                        }
                    }

                    if (allGranted) {
                        // Se todas as permissões foram concedidas, tenta conectar ao ESP32.
                        Toast.makeText(this, "Permissões Bluetooth concedidas!", Toast.LENGTH_SHORT).show();
                        connectToEsp32();
                    } else {
                        // Se alguma permissão foi negada, informa o usuário.
                        Toast.makeText(this, "Permissões Bluetooth são necessárias para continuar.", Toast.LENGTH_LONG).show();
                    }
                });

        // Configura o listener para o botão de conexão.
        btnConnect.setOnClickListener(v -> checkAndRequestPermissions());

        // Configura os listeners para os botões de controle do carrinho.
        // Cada botão enviará um caractere específico para o ESP32.
        btnForward.setOnClickListener(v -> sendCommand('F')); // 'F' para Frente
        btnBackward.setOnClickListener(v -> sendCommand('R')); // 'R' para Trás
        btnLeft.setOnClickListener(v -> sendCommand('E'));     // 'E' para Esquerda
        btnRight.setOnClickListener(v -> sendCommand('D'));   // 'D' para Direita
        btnStop.setOnClickListener(v -> sendCommand('-'));     // '-' para Parar

    }

    /**
     * Verifica se o Bluetooth está ativado e se as permissões necessárias foram concedidas.
     * Se não, solicita-as.
     */
    private void checkAndRequestPermissions() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Adaptador Bluetooth não disponível.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Se o Bluetooth estiver desligado, solicita ao usuário para ligá-lo.
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1); // REQUEST_ENABLE_BT = 1
            return; // Espera o resultado da ativação do Bluetooth.
        }

        // Verifica as permissões de Bluetooth e localização em tempo de execução,
        // diferenciando para versões do Android.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) { // Android 12 (API 31) e superior
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                requestPermissionsLauncher.launch(new String[]{ // Solicita as permissões
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION
                });
            } else {
                // Permissões já concedidas, tenta conectar.
                connectToEsp32();
            }
        } else { // Android 11 (API 30) e inferior
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                requestPermissionsLauncher.launch(new String[]{ // Solicita as permissões
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION // Necessário para escaneamento em API 23+
                });
            } else {
                // Permissões já concedidas, tenta conectar.
                connectToEsp32();
            }
        }
    }

    // Lida com o resultado da solicitação para ligar o Bluetooth (onActivityResult é deprecated, mas ainda funcional para este caso simples)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) { // Verifica se o requestCode é o da solicitação de ativação do Bluetooth
            if (resultCode == RESULT_OK) {
                // Bluetooth foi ligado com sucesso, agora pode tentar conectar.
                Toast.makeText(this, "Bluetooth ligado.", Toast.LENGTH_SHORT).show();
                checkAndRequestPermissions(); // Recheca permissões e tenta conectar
            } else {
                // Usuário negou ligar o Bluetooth.
                Toast.makeText(this, "Bluetooth é necessário para a conexão com o ESP32.", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Tenta conectar ao dispositivo ESP32 pareado.
     * Esta operação é feita em uma nova Thread para não bloquear a UI.
     */
    private void connectToEsp32() {
        new Thread(() -> { // Cria uma nova Thread
            BluetoothDevice esp32Device = null;

            // Verifica a permissão BLUETOOTH_CONNECT para acessar dispositivos pareados.
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permissão BLUETOOTH_CONNECT não concedida. Não é possível listar dispositivos pareados.");
                runOnUiThread(() -> Toast.makeText(this, "Permissão BLUETOOTH_CONNECT é necessária para conectar.", Toast.LENGTH_LONG).show());
                return;
            }

            // Obtém a lista de dispositivos Bluetooth pareados.
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                // Procura o ESP32 pelo nome definido (ESP32_DEVICE_NAME).
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getName() != null && device.getName().equals(ESP32_DEVICE_NAME)) {
                        esp32Device = device;
                        break;
                    }
                }
            }

            if (esp32Device == null) {
                // Se o ESP32 não foi encontrado entre os pareados, informa o usuário.
                runOnUiThread(() -> { // Atualiza a UI na Thread principal
                    Toast.makeText(this, "Certifique-se de que o ESP32 '" + ESP32_DEVICE_NAME + "' está ligado e pareado com este celular.", Toast.LENGTH_LONG).show();
                    bluetoothStatusImageView.setVisibility(View.GONE);
                });
                return;
            }

            // Tenta criar um socket Bluetooth RFCOMM e se conectar.
            try {
                btSocket = esp32Device.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothAdapter.cancelDiscovery(); // Cancela a descoberta para otimizar a conexão.
                btSocket.connect(); // Tenta a conexão.
                outputStream = btSocket.getOutputStream(); // Obtém o stream de saída para enviar dados.

                runOnUiThread(() -> { // Atualiza a UI na Thread principal
                    Toast.makeText(this, "Conectado ao ESP32!", Toast.LENGTH_SHORT).show();
                    setControlButtonsEnabled(true); // Habilita os botões de controle
                    btnConnect.setText("Desconectar"); // Altera o texto do botão de conectar
                    btnConnect.setOnClickListener(v -> disconnectEsp32()); // Troca a ação do botão para desconectar
                    bluetoothStatusImageView.setVisibility(View.VISIBLE);
                });

            } catch (IOException e) {
                // Loga o erro e informa o usuário em caso de falha na conexão.
                Log.e(TAG, "Erro ao conectar ao ESP32: " + e.getMessage());
                try {
                    if (btSocket != null) btSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "Erro ao fechar o socket após falha de conexão: " + e2.getMessage());
                }
                runOnUiThread(() -> { // Atualiza a UI na Thread principal
                    Toast.makeText(this, "Falha ao conectar ao ESP32. Tente novamente.", Toast.LENGTH_LONG).show();
                    setControlButtonsEnabled(false); // Desabilita botões de controle
                    bluetoothStatusImageView.setVisibility(View.GONE);
                });
            }
        }).start(); // Inicia a nova Thread.
    }

    /**
     * Desconecta do dispositivo ESP32, fechando streams e sockets.
     */
    private void disconnectEsp32() {
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Erro ao fechar outputStream: " + e.getMessage());
            }
        }
        if (btSocket != null) {
            try {
                btSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Erro ao fechar btSocket: " + e.getMessage());
            }
        }
        outputStream = null;
        btSocket = null;
        runOnUiThread(() -> { // Atualiza a UI na Thread principal
            Toast.makeText(this, "Desconectado do ESP32.", Toast.LENGTH_SHORT).show();
            setControlButtonsEnabled(false); // Desabilita botões de controle
            btnConnect.setText("Conectar ao ESP32"); // Retorna o texto do botão de conectar
            btnConnect.setOnClickListener(v -> checkAndRequestPermissions()); // Retorna a ação do botão para conectar
            bluetoothStatusImageView.setVisibility(View.GONE);
        });
    }

    /**
     * Envia um comando de caractere para o ESP32 via Bluetooth.
     *
     * @param command O caractere do comando a ser enviado ('F', 'D', 'E', 'R', '-').
     */
    private void sendCommand(char command) {
        if (outputStream != null) { // Verifica se há uma conexão ativa
            try {
                outputStream.write(command); // Converte o caractere para byte e envia
                Toast.makeText(this, "Comando '" + command + "' enviado.", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Comando enviado: " + command); // Loga o comando enviado
            } catch (IOException e) {
                Log.e(TAG, "Erro ao enviar comando: " + e.getMessage());
                Toast.makeText(this, "Erro ao enviar comando. Reconecte o Bluetooth.", Toast.LENGTH_LONG).show();
                disconnectEsp32(); // Tenta desconectar se houver erro no envio
            }
        } else {
            Toast.makeText(this, "Bluetooth não conectado. Por favor, conecte primeiro.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Habilita ou desabilita os botões de controle de movimento do carrinho.
     * @param enabled true para habilitar, false para desabilitar.
     */
    private void setControlButtonsEnabled(boolean enabled) {
        btnForward.setEnabled(enabled);
        btnBackward.setEnabled(enabled);
        btnLeft.setEnabled(enabled);
        btnRight.setEnabled(enabled);
        btnStop.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectEsp32(); // Garante que a conexão Bluetooth seja fechada ao destruir a atividade
    }
}
