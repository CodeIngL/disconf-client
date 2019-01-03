package cn.com.servyou.yypt.remote.deploy;

import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * <p>Description: </p>
 * <p>税友软件集团有限公司</p>
 *
 * @author laihj
 * 2019/1/3
 */
public class DeployListenerTest {

    /**
     * just for Test
     *
     * @param args
     */
    public static void main(String[] args) {
        Map<String, String> parameter = new HashMap<String, String>();
        parameter.put("version", "1.0.0");
        parameter.put("app", "nbgl_wlgl");
        parameter.put("env", "dev");
        parameter.put("key", "filter-docker-spare.properties");
        parameter.put("type", "0");
        StringBuilder builder = new StringBuilder("http://192.168.150.165:9002/servyconf/api/config/file");
        builder.append("?");
        for (String thisKey : parameter.keySet()) {
            String cur = thisKey + "=" + parameter.get(thisKey);
            cur += "&";
            builder.append(cur);
        }
        if (builder.length() > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }
        try {
            URL url = new URL(builder.toString());
            System.out.println(url);
            File file = new File(System.getProperty("user.dir") + File.separator + "tmp");
            if (!file.exists()) {
                file.mkdir();
            }
            File tmpFile = new File(file, "dconf.properties");
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            FileUtils.copyURLToFile(url, tmpFile);
            String readline;
            BufferedReader reader = new BufferedReader(new FileReader(tmpFile));
            while ((readline = reader.readLine()) != null) System.out.println(readline);
        } catch (Exception e) {
            System.out.println(e);
        }
    }

}