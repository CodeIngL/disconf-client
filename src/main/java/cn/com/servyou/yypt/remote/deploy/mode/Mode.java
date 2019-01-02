package cn.com.servyou.yypt.remote.deploy.mode;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>Description: </p>
 * <p>税友软件集团有限公司</p>
 *
 * @author laihj
 * 2019/1/2
 */
public enum Mode {

    LOCAL("false", "local"), REMOTE("true", "remote");

    private String modeMark;

    private String modeDes;

    Mode(String modeMark, String modeDes) {
        this.modeMark = modeMark;
        this.modeDes = modeDes;
    }

    static final Map<String,Mode> RANGE = new HashMap<String, Mode>();

    static {
        RANGE.put("false",LOCAL);
        RANGE.put("true",REMOTE);
    }

    public static boolean match(String mark){
        return RANGE.containsKey(mark);
    }
}
