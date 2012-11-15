# wowza-geoip-simple

Simplistic GeoIP module for Wowza Media Server

## Description

A GeoIP module that supports denying access to certain countries, or blocking
all countries except a predefined set of countries.

Supports exception files per application that will be re-read on the fly
after changes have been made.

The module has been designed with ease of use as the main concern, and
only requires one configuration property per application, after loading the
module in the relevant Application.xml files.

## Credits

The module was written back in 2008, as an RTMP-only geoip-blocker, and
updated more recently with HTTP and RTSP support.

Support for CIDR-based exceptions was added by incorporating two functions
posted online by Charles Johnson (http://www.technojeeves.com/).

The GeoIP library used in this module is created by MaxMind, and is
LGPL-licensed: http://dev.maxmind.com/geoip/downloadable#Java-8

## Known problems / limitations

The module does unfortunately *not* support IPv6 yet.

If you mix short country codes and full country names and add (for example)
"NO" to geoipDenyCountries and "Norway" to geoipAllowCountries, you will
confuse the geoip module, and get unpredictable results.
Using "Norway" in both properties will make geoipAllowCountries take
precedence over geoipDenyCountries, to err on the side of caution.

## Prerequisites

This module depends on the GeoLite Country database from MaxMind:
http://dev.maxmind.com/geoip/geolite

http://geolite.maxmind.com/download/geoip/database/GeoLiteCountry/GeoIP.dat.gz

## Installation

Copy lib/availo-geoip-3.1.2.jar or lib/availo-geoip-3.5.0.jar to the 'lib/'-
folder of the Wowza Media Server of a corresponding version.

(The modules are currently identical, and should work in all Wowza versions,
including Wowza 2.x. This may change in the future, however.)

## Configuration

### Creating the geoip-application

```bash
mkdir /usr/local/WowzaMediaServer/applications/geoip
mkdir /usr/local/WowzaMediaServer/conf/geoip
# Feel free to copy an existing application's Application.xml file instead
cp -p /usr/local/WowzaMediaServer/conf/Application.xml /usr/local/WowzaMediaServer/conf/geoip
```

Configure the new Application.xml corresponding to your needs, the same way
you would configure a regular, non-geoip application.
When the application is working as intended, add the following to &lt;Modules&gt;:

### Loading the module

```xml
<Module>
	<Name>ModuleLoadBalancerRedirector</Name>
	<Description>ModuleLoadBalancerRedirector</Description>
	<Class>com.availo.wms.plugin.vhostloadbalancer.ModuleLoadBalancerRedirector</Class>
</Module>
```

### Configuration properties
... and the following property to &lt;Properties&gt; at the bottom of the file:

```xml
<Property>
	<Name>geoipAllowCountries</Name>
	<!--
		Multiple countries can be specified by using "|" as a separator
		This example allows access to the three nordic countries.
		"Norway|Sweden|Denmark", or a combination of short and long names
		would work fine as well.
	-->
	<Value>NO|SE|DK</Value>
</Property>
```

### Optional configuration properties

```xml

<!-- All available configuration properties, and their default values -->

<Property>
	<!--
		Whether to allow all countries unless explicitly denied in
		geoipDenyCountries, or to only accept countries defined in
		geoipAllowCountries. The latter, "false", is the default.
	-->
	<Name>geoipDefaultPermit</Name>
	<Value>false</Value>
	<Type>Boolean</Type>
</Property>

	<!-- Where the GeoIP database can be found -->
<Property>
	<Name>geoipDatabasePath</Name>
	<Value>/usr/local/share/GeoIP/GeoIP.dat</Value>
</Property>

<Property>
	<!-- See "Configuration properties" for examples -->
	<Name>geoipAllowCountries</Name>
	<Value></Value>
</Property>

<Property>
	<!-- Uses the same syntax as geoipAllowCountries -->
	<Name>geoipDenyCountries</Name>
	<Value></Value>
</Property>

### Adding exceptions on the fly

All the configuration properties requires a restart of the application
after changing the values. This only happens if the application is inactive
for a certain period, a restart of the vhost or a restart of Wowza.

A slightly less effective option is available for situations where restarting
the application is impossible, by using an exceptions file.

This can be used by creating a file called "geoip.exceptions" inside the
application's config directory, alongside Application.xml.

The file accepts countries, IP addresses and CIDR.
Each exception should be placed on a separate line, like this:

``
127.0.0.1
192.168.0.0/24
Norway
10.4.20.0/23
UK
``

### Debugging

If you encounter problems with the module, please change "INFO" to "DEBUG"
in log4j.properties, just below "APPLICATION LEVEL LOGGING CONFIG - START"

This will make the module a lot more verbose, and should be helpful when
trying to figure out the problem.
