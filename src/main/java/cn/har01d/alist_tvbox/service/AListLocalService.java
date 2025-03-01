package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.SettingResponse;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static cn.har01d.alist_tvbox.util.Constants.ALIST_RESTART_REQUIRED;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_START_TIME;

@Slf4j
@Service

public class AListLocalService {

    private final SettingRepository settingRepository;
    private final SiteRepository siteRepository;
    private final RestTemplate restTemplate;

    private volatile int aListStatus;

    public AListLocalService(SettingRepository settingRepository, SiteRepository siteRepository, AppProperties appProperties, RestTemplateBuilder builder) {
        this.settingRepository = settingRepository;
        this.siteRepository = siteRepository;
        this.restTemplate = builder.rootUri("http://localhost:" + (appProperties.isHostmode() ? "5234" : "5244")).build();
    }

    @PostConstruct
    public void setup() {
        String url = settingRepository.findById("open_token_url").map(Setting::getValue).orElse("https://api.xhofe.top/alist/ali_open/token");
        Utils.executeUpdate("INSERT INTO x_setting_items VALUES('open_token_url','" + url + "','','string','',1,0);");
        String token = settingRepository.findById("token").map(Setting::getValue).orElse("");
        Utils.executeUpdate("UPDATE x_setting_items SET value = '" + StringUtils.isNotBlank(token) + "' WHERE key = 'sign_all'");
        String time = settingRepository.findById("delete_delay_time").map(Setting::getValue).orElse("900");
        Utils.executeUpdate("UPDATE x_setting_items SET value = '" + time + "' WHERE key = 'delete_delay_time'");
    }

    public void updateSetting(String key, String value, String type) {
        log.info("update setting {}={}", key, value);
        if (getAListStatus() == 2) {
            Map<String, Object> body = new HashMap<>();
            body.put("key", key);
            body.put("value", value);
            body.put("type", type);
            body.put("flag", 1);
            body.put("group", 4);
            body.put("help", "");
            body.put("options", "");
            HttpHeaders headers = new HttpHeaders();
            Site site = siteRepository.findById(1).orElseThrow();
            headers.add("Authorization", site.getToken());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            SettingResponse response = restTemplate.postForObject("/api/admin/setting/update", entity, SettingResponse.class);
            log.info("update setting by API: {}", response);
        } else {
            int code = Utils.executeUpdate(String.format("UPDATE x_setting_items SET value = '%s' WHERE key = '%s'", key, value));
            log.info("update setting by SQL: {}", code);
        }
    }

    public void startAListServer() {
        if (aListStatus > 0) {
            return;
        }

        try {
            log.info("start AList server");
            ProcessBuilder builder = new ProcessBuilder();
            File outFile = new File("/opt/atv/log/app.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
            builder.command("/opt/alist/alist", "server", "--no-prefix");
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            settingRepository.save(new Setting(ALIST_RESTART_REQUIRED, "false"));
            settingRepository.save(new Setting(ALIST_START_TIME, Instant.now().toString()));
            log.info("AList server starting, PID: {}", process.pid());
            aListStatus = 1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void stopAListServer() {
        log.info("stop AList server");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            File outFile = new File("/opt/atv/log/app.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(outFile));
            builder.command("pkill", "-f", "/opt/alist/alist");
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            process.waitFor(1, TimeUnit.SECONDS);
            aListStatus = 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void restartAListServer() {
        stopAListServer();
        startAListServer();
    }

    public void validateAListStatus() {
        getAListStatus();
        if (aListStatus == 1) {
            throw new BadRequestException("AList服务启动中");
        }
        if (aListStatus == 0) {
            throw new BadRequestException("AList服务未启动");
        }
    }

    public int getAListStatus() {
        try {
            ResponseEntity<SettingResponse> response = restTemplate.getForEntity("/api/public/settings", SettingResponse.class);
            if (response.getBody() != null) {
                if (response.getBody().getCode() == 200) {
                    aListStatus = 2;
                    return 2;
                } else if (response.getBody().getCode() == 500) {
                    aListStatus = 1;
                    return 1;
                }
            }
        } catch (Exception e) {
            log.warn("{}", e.getMessage());
        }
        aListStatus = 0;
        return 0;
    }

}
