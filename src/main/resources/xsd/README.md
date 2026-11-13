# CPPA 2.0 schemas

Vendored so that CPA validation never reaches the network. `cpp-cpa-2_0.xsd` imports the other
three by relative `schemaLocation`, and `CpaXsdValidator` pre-loads all four from the classpath
with external DTD and schema access disabled — an attacker-supplied document must not be able
to pull in a local file or a remote schema.

| File | Origin |
|---|---|
| `cpp-cpa-2_0.xsd` | OASIS ebXML CPP/A 2.0 |
| `xmldsig-core-schema.xsd` | W3C XML Signature |
| `xlink.xsd`, `xml.xsd` | W3C |

Taken from ebms-core (`core/src/main/resources/nl/clockwork/ebms/xsd/`, Apache 2.0), which
validates uploads against the same set — so a CPA it accepts is one we accept.

Do not edit them.
