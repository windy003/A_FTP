package com.example.ftpserver;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.widget.Toast;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.ConnectionConfigFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.io.File;

public class MainActivity extends AppCompatActivity {
    private FtpServer server;
    private TextView statusText;
    private Button toggleButton;
    private static final int PORT = 2121;
    private static final int PERMISSION_REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);
        
        toggleButton.setOnClickListener(v -> {
            if (server != null && !server.isStopped()) {
                stopFtpServer();
            } else {
                checkPermissionAndStartServer();
            }
        });
    }

    private void checkPermissionAndStartServer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    PERMISSION_REQUEST_CODE);
        } else {
            startFtpServer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startFtpServer();
            } else {
                Toast.makeText(this, "需要存储权限才能运行FTP服务器", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startFtpServer() {
        try {
            FtpServerFactory serverFactory = new FtpServerFactory();
            ListenerFactory factory = new ListenerFactory();
            
            factory.setPort(PORT);
            
            PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
            UserManager userManager = userManagerFactory.createUserManager();
            
            BaseUser anonymousUser = new BaseUser();
            anonymousUser.setName("anonymous");
            anonymousUser.setPassword("");
            
            // 直接使用外部存储根目录（储存卡）
            File homeDir = Environment.getExternalStorageDirectory();
            if (homeDir != null && homeDir.exists()) {
                anonymousUser.setHomeDirectory(homeDir.getAbsolutePath());
            } else {
                // 如果外部存储不可用，使用应用私有目录作为备选
                homeDir = getExternalFilesDir(null);
                if (homeDir != null) {
                    anonymousUser.setHomeDirectory(homeDir.getAbsolutePath());
                } else {
                    anonymousUser.setHomeDirectory(getFilesDir().getAbsolutePath());
                }
            }
            
            List<Authority> authorities = new ArrayList<>();
            authorities.add(new WritePermission());
            authorities.add(new ConcurrentLoginPermission(10, 10));
            authorities.add(new TransferRatePermission(0, 0));
            anonymousUser.setAuthorities(authorities);
            
            userManager.save(anonymousUser);
            serverFactory.setUserManager(userManager);
                
            ConnectionConfigFactory configFactory = new ConnectionConfigFactory();
            configFactory.setAnonymousLoginEnabled(true);
            serverFactory.setConnectionConfig(configFactory.createConnectionConfig());
            
            serverFactory.addListener("default", factory.createListener());
            
            server = serverFactory.createServer();
            server.start();
            
            String ipAddress = getLocalIpAddress();
            statusText.setText("FTP服务器运行中\n" +
                    "IP: " + ipAddress + "\n" +
                    "端口: " + PORT + "\n" +
                    "根目录: " + homeDir.getAbsolutePath() + "\n" +
                    "支持匿名访问");
            toggleButton.setText("停止服务器");
            
        } catch (Exception e) {
            e.printStackTrace();
            statusText.setText("启动服务器失败: " + e.getMessage());
        }
    }

    private void stopFtpServer() {
        if (server != null) {
            server.stop();
            statusText.setText("FTP服务器已停止");
            toggleButton.setText("启动服务器");
        }
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); 
                 en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); 
                     enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "未知";
    }
} 