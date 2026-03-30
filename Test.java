import java.net.*;
import java.io.*;
import java.util.Scanner;
public class Test {
    public static void main(String[] args) throws Exception {
        HttpURLConnection c1 = (HttpURLConnection) new URL("http://localhost:8080/api/auth/register").openConnection();
        c1.setRequestMethod("POST");
        c1.setDoOutput(true);
        c1.getOutputStream().write("name=Y&email=ny@y.com&password=y".getBytes());
        c1.getResponseCode();
        HttpURLConnection c2 = (HttpURLConnection) new URL("http://localhost:8080/api/auth/login").openConnection();
        c2.setRequestMethod("POST");
        c2.setDoOutput(true);
        c2.getOutputStream().write("email=ny@y.com&password=y".getBytes());
        Scanner s = new Scanner(c2.getInputStream());
        String res = s.nextLine();
        String token = res.split(""token": "")[1].split(""")[0];

        HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:8080/api/docs/upload").openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("X-Token", token);
        c.setRequestProperty("X-File-Name", "test.pdf");
        c.setRequestProperty("X-Notes", "");
        c.setDoOutput(true);
        c.getOutputStream().write("dummy".getBytes());
        InputStream is;
        if (c.getResponseCode() >= 400) { is = c.getErrorStream(); } else { is = c.getInputStream(); }
        System.out.println(new String(is.readAllBytes()));
    }
}
