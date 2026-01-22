package bgu.spl.net.impl.data;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Database {

    private static class Instance {
        private static final Database instance = new Database();
    }

    public static Database getInstance() {
        return Instance.instance;
    }

    private final String sqlHost = "127.0.0.1";
    private final int sqlPort = 7778;

    private Database() {}

    private String executeSQL(String sql) {
        try (Socket socket = new Socket(sqlHost, sqlPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.print(sql + '\0');
            out.flush();

            StringBuilder resp = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && ch != '\0') {
                resp.append((char) ch);
            }
            return resp.toString();

        } catch (Exception e) {
            System.err.println("[DB] SQL server error: " + e.getMessage());
            return "ERROR|" + e.getMessage();
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String now() {
        return LocalDateTime.now().format(TS_FMT);
    }

    private boolean isSuccess(String resp) {
        return resp != null && resp.startsWith("SUCCESS");
    }

    private List<String[]> parseRows(String resp) {
        if (resp == null) return Collections.emptyList();
        if (!resp.startsWith("SUCCESS")) return Collections.emptyList();
        String[] parts = resp.split("\\|", -1);
        if (parts.length <= 1) return Collections.emptyList();

        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String row = parts[i];
            if (row == null || row.isEmpty()) continue;
            rows.add(row.split(",", -1));
        }
        return rows;
    }

    public String getPassword(String username) {
        String sql = "SELECT password FROM users WHERE username='" + esc(username) + "'";
        String resp = executeSQL(sql);
        List<String[]> rows = parseRows(resp);
        if (rows.isEmpty()) return null;
        return rows.get(0).length > 0 ? rows.get(0)[0] : null;
    }

    public boolean registerUser(String username, String password) {
        String sql = "INSERT INTO users(username,password) VALUES('" + esc(username) + "','" + esc(password) + "')";
        String resp = executeSQL(sql);
        return isSuccess(resp);
    }

    public void logLogin(String username) {
        String sql = "INSERT INTO sessions(username,login_time,logout_time) VALUES('" + esc(username) + "','" + esc(now()) + "',NULL)";
        executeSQL(sql);
    }

    public void logLogout(String username) {
        String ts = esc(now());
        String u = esc(username);
        String sql = "UPDATE sessions SET logout_time='" + ts + "' WHERE id=(SELECT id FROM sessions WHERE username='" + u + "' AND logout_time IS NULL ORDER BY id DESC LIMIT 1)";
        executeSQL(sql);
    }

    /** Log a filename uploaded via report command. */
    public void trackFileUpload(String username, String filename, String gameChannel) {
        String sql = "INSERT INTO file_logs(username,filename,game_channel,upload_time) VALUES('" + esc(username) + "','" + esc(filename) + "','" + esc(gameChannel) + "','" + esc(now()) + "')";
        executeSQL(sql);
    }

    public void printReport() {
        System.out.println("\n========== SERVER SQL REPORT (" + LocalDateTime.now() + ") ==========");

        System.out.println("\n1) Registered users:");
        List<String[]> users = parseRows(executeSQL("SELECT username FROM users ORDER BY username"));
        if (users.isEmpty()) {
            System.out.println("   (none)");
            System.out.println("======================================================\n");
            return;
        }
        for (String[] r : users) {
            System.out.println("   - " + r[0]);
        }

        System.out.println("\n2) Login history:");
        for (String[] u : users) {
            String user = u[0];
            System.out.println("\n   User: " + user);
            List<String[]> sess = parseRows(executeSQL(
                    "SELECT login_time, COALESCE(logout_time,'') FROM sessions WHERE username='" + esc(user) + "' ORDER BY id"));
            if (sess.isEmpty()) {
                System.out.println("      (no sessions)");
            } else {
                for (String[] s : sess) {
                    String login = s.length > 0 ? s[0] : "";
                    String logout = s.length > 1 ? s[1] : "";
                    System.out.println("      login=" + login + " logout=" + (logout.isEmpty() ? "(still logged in)" : logout));
                }
            }
        }

        System.out.println("\n3) Filenames uploaded via report:");
        for (String[] u : users) {
            String user = u[0];
            System.out.println("\n   User: " + user);
            List<String[]> files = parseRows(executeSQL(
                    "SELECT filename, COALESCE(game_channel,''), upload_time FROM file_logs WHERE username='" + esc(user) + "' ORDER BY id"));
            if (files.isEmpty()) {
                System.out.println("      (no files)");
            } else {
                for (String[] f : files) {
                    String fname = f.length > 0 ? f[0] : "";
                    String chan = f.length > 1 ? f[1] : "";
                    String time = f.length > 2 ? f[2] : "";
                    System.out.println("      file='" + fname + "' channel='" + chan + "' time=" + time);
                }
            }
        }

        System.out.println("======================================================\n");
    }
}
