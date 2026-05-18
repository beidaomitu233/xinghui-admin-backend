package com.xinghuiTec.mail.utils;

import cn.hutool.extra.mail.JakartaMail;
import cn.hutool.extra.mail.MailAccount;
import com.xinghuiTec.utils.SpringUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

/**
 * 邮件工具类
 *
 * @author xinghuiTec
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MailUtils {

    private static final MailAccount ACCOUNT = SpringUtils.getBean(MailAccount.class);

    /** 发送文本邮件 */
    public static String sendText(String to, String subject, String content) {
        return send(to, subject, content, false);
    }

    /** 发送 HTML 邮件 */
    public static String sendHtml(String to, String subject, String content) {
        return send(to, subject, content, true);
    }

    /** 发送文本邮件（带附件） */
    public static String sendText(String to, String subject, String content, File... files) {
        return send(to, subject, content, false, files);
    }

    /** 发送 HTML 邮件（带附件） */
    public static String sendHtml(String to, String subject, String content, File... files) {
        return send(to, subject, content, true, files);
    }

    /** 发送邮件 */
    public static String send(String to, String subject, String content, boolean isHtml, File... files) {
        return JakartaMail.create(ACCOUNT)
            .addTos(to.split("[,;]"))
            .setTitle(subject)
            .setContent(content)
            .setHtml(isHtml)
            .setFiles(files)
            .send();
    }

    /** 发送 HTML 邮件（带内嵌图片，cid:xxx 引用） */
    public static String sendHtml(String to, String subject, String content, Map<String, InputStream> imageMap) {
        JakartaMail mail = JakartaMail.create(ACCOUNT)
            .addTos(to.split("[,;]"))
            .setTitle(subject)
            .setContent(content)
            .setHtml(true);
        if (imageMap != null) {
            imageMap.forEach(mail::addImage);
        }
        return mail.send();
    }
}
