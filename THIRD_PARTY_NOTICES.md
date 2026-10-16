# Third-party notices and dependency review

## Project and upstream attribution

The root MIT license applies to original contributions by Xinghui Admin contributors. Existing copyright headers and author attributions have been retained; this license does not replace another author's license. The backend upstream repository is [xinghui-admin-backend](https://github.com/beidaomitu233/xinghui-admin-backend). The companion frontend [xinghui-admin-vue3](https://github.com/beidaomitu233/xinghui-admin-vue3) has its own upstream notices and licensing.

The seven user exception classes under `common/common-core/src/main/java/com/xinghuiTec/exception/user/` retain `@author ruoyi` attribution (BlackListException, CaptchaException, CaptchaExpireException, UserException, UserNotExistsException, UserPasswordNotMatchException and UserPasswordRetryLimitExceedException). The [RuoYi upstream](https://github.com/yangzongzhuan/RuoYi) publishes its [MIT notice](https://github.com/yangzongzhuan/RuoYi/blob/master/LICENSE); its original copyright and permission are reproduced in [licenses/RuoYi-MIT.txt](licenses/RuoYi-MIT.txt). This identifies visible attribution evidence, not a claim that an exact historical upstream revision was reconstructed.

This candidate distributes source, POMs, migrations, scripts and documentation. It does not commit an executable fat JAR, Maven dependencies or other dependency binaries. Dependencies fetched by Maven remain under their own terms. Any future binary distribution needs a review of its actual contents and corresponding license/notice/source requirements.

## Inventory and evidence scope

[dependencies.json](licenses/dependencies.json) records coordinates and license declarations from cached POMs (including parent inheritance) for an actual packaged runtime and an executed test classpath of the reviewed application snapshot. It also lists all 16 first-party POM files. This is an observed dependency inventory, not a freshly resolved complete Maven dependency tree, plugin inventory, security audit or legal compatibility guarantee. Runtime and test scopes are identified separately; versions selected by Maven can differ from a root property. Build tools, other profiles and future resolutions may add components.

Common declarations include Apache 2.0, MIT, BSD, MulanPSL 2, Eclipse licenses, LGPL and CDDL/GPL alternatives. The JSON preserves upstream wording rather than silently converting ambiguous declarations into SPDX identifiers or selecting a license on behalf of the recipient.

## Items requiring particular attention

- MySQL Connector/J 8.0.33 declares GPL v2 with Universal FOSS Exception v1.0 in its POM. See [Oracle licensing](https://www.mysql.com/about/legal/licensing/oem/) and the exact downloaded component's license materials before redistribution. This project does not relicense the connector under MIT.
- JAXB, Activation and related libraries include CDDL/GPL with Classpath Exception and Eclipse license declarations. Preserve their exact version-specific notices and choices when packaging.
- Some components declare LGPL. Inspect the corresponding version's terms and distribution form before shipping a bundle; dependency use is not a blanket project license grant.
- Several POMs have no usable license declaration or contain empty entries (including selected Aliyun SDK modules, H2, Activation, XMLBeans, Bouncy Castle and JDOM versions). Embedded license files were found for Activation, XMLBeans and JDOM; absence in a POM is not proof that a component is unlicensed. Relevant upstream references include [Bouncy Castle](https://www.bouncycastle.org/license.html) and [H2](https://h2database.github.io/html/license.html). Exact cached/version evidence and outstanding checks are retained privately for review.
- The runtime inventory records unresolved metadata for a duplicated JAXB core coordinate and Spring Boot jarmode tools. These are inventory limitations, not source files copied into this repository. Resolve them against the exact release artifact before any binary redistribution.

The existing RuoYi-attributed source is identified above. No dependency binaries or vendor assets were added during candidate preparation. Existing attribution headers were preserved. Static review cannot determine the complete authorship provenance of every pre-existing line; contributors must retain required upstream notices for any copied or adapted material.
