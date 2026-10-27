# Android 通用正式签名文件说明

本项目包含正式的 Android 签名配置支持，签名文件为标准 Android 4096 位 RSA 正式签名凭据，不仅服务于当前 AlphaPro 项目，亦可作为个人/团队全套 Android 项目的通用签名。

---

## 安全规范（重要）

为保障开发者密钥安全，**正式签名文件及密码文件已被 `.gitignore` 全局忽略**，绝不会提交到公共 Git 仓库：
- `keystore/release.jks`（密钥文件，已忽略）
- `keystore/keystore.properties`（密码配置文件，已忽略）

---

## 本地签名配置方法

如需在本地进行正式签名打包，请复制本目录下的模板文件：

```bash
cp keystore/keystore.properties.example keystore/keystore.properties
```

然后在本地 `keystore/keystore.properties` 中填写真实的密码信息即可：

```properties
storeFile=../keystore/release.jks
storePassword=<YOUR_STORE_PASSWORD>
keyAlias=letter
keyPassword=<YOUR_KEY_PASSWORD>
```

---

## 密钥元信息

- **签名算法**：RSA 4096 位 (SHA384withRSA)
- **密钥别名 (Alias)**：`letter`
- **有效期**：10,950 天（约 30 年，至 2056 年）
- **证书 DName**：`CN=Letter, OU=Developer, O=Letter Studio, L=Hangzhou, ST=Zhejiang, C=CN`
