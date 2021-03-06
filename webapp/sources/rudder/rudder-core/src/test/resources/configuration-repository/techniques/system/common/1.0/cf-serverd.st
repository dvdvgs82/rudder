#####################################################################################
# Copyright 2011 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

#######################################################
#
# Server specific configuration
#
#######################################################

bundle server access_rules
{
  vars:
    enterprise_edition::
      "query_types" slist => {"delta", "rebase", "full"};

  # Access rules are only defined on a policy server. Standard nodes should not share any files.
  access:

    policy_server|role_rudder_relay_promises_only::
      "${def.dir_masterfiles}"
        handle    => "grant_access_policy",
        comment   => "Grant access to the policy updates",
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      "${g.rudder_ncf_origin_common}"
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      "${g.rudder_ncf_origin_local}"
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      # Deny access to 50_techniques folder
      "${g.rudder_ncf_origin_local}/50_techniques"
        deny_ips  => {  "0.0.0.0/0"  };

&if(MANAGED_NODES_NAME)&

      &if(SHARED_FILES_FOLDER)&
      "&SHARED_FILES_FOLDER&"
        comment   => "Grant access to the share files",
        maproot => {  @{def.acl}  },
        admit_ips => {  @{def.acl}  };

      &endif&

    role_rudder_cfengine_mission_portal::
      "${query_types}"
        handle             => "server_access_grant_${query_types}_for_hub",
        comment            => "Grant ${query_types} reporting query for the hub on the policy server",
        resource_type      => "query",
        report_data_select => rudder_data_select_policy_hub,
        admit_ips          => { host2ip("${server_info.policy_server}"), @{sys.ip_addresses} }; # an enterprise policy server needs to be able to contact itself


    any::
    &MANAGED_NODES_NAME, MANAGED_NODES_ID, MANAGED_NODES_KEY : {host, uuid, key |
      "/var/rudder/share/&uuid&/"
        maproot => {  @{def.acl}  },
        admit_keys => { "&key&" };

      "/var/rudder/shared-files/&uuid&/"
        maproot => {  @{def.acl}  },
        admit_keys => { "&key&" };

    } &

&endif&

    # Allow server to remotely run the agent
    # Using ${sys.cf_agent} fails, as cf-serverd cannot canonize its path
    any::
      "${g.rudder_command}" comment => "Grant access to rudder agent command for cfruncommand",
        admit_ips => { host2ip("${server_info.policy_server}") };

    enterprise_edition::
      "${query_types}"
        handle             => "server_access_grant_${query_types}_for_hosts",
        comment            => "Grant ${query_types} reporting query for the hub on the hosts",
        resource_type      => "query",
        report_data_select => rudder_data_select_host,
        admit_ips          => { host2ip("${server_info.policy_server}"), @{sys.ip_addresses} };

  roles:
      # Allow user root to set any class
      ".*"  authorize => { "root" };
}

bundle common def
{
  vars:
      "cf_runagent_shell" 
        string  => "/bin/sh",
        comment => "Define path to shell used by cf-runagent";

      "dir_masterfiles" string => translatepath("${sys.workdir}/masterfiles");

   # List here the IP masks that we grant access to on the server

    policy_server::
      "acl" slist => {
        "127.0.0.0/8" , "::1", @{sys.ip_addresses},
&if(AUTHORIZED_NETWORKS)&
        host2ip("${server_info.policy_server}"), # the policy server can connect to a relay
        &AUTHORIZED_NETWORKS:{net|"&net&",}&
&endif&

    };

    !policy_server::
      "acl" slist => {
        host2ip("${server_info.policy_server}")
      };

  classes:
      "ipv6_enabled" expression => fileexists("/proc/net/if_inet6"),
                          scope => "namespace";

}


body server control
{
        trustkeysfrom     => {
          @{def.acl} ,
        }; #trustkey allows the exchange of keys

        allowconnects     => {
          @{def.acl} ,
        };

        allowallconnects  => {
          @{def.acl} ,
        };

        # force connections via tls1.2
        allowtlsversion => "1.2";

        maxconnections    => "1000";
        logallconnections => "true";

        # Disallow old protocol
        allowlegacyconnects => {};

        # last single quote in cfruncommand is left open, so that
        # arguments (like -K and --remote-bundles) are properly appended.
        cfruncommand => "${g.rudder_command} agent run -uR";

        allowusers        => {
          "&POLICYSERVER_ADMIN&",
          &MANAGED_NODES_ADMIN : {admin | "&admin&" };separator=", "&
        };

        denybadclocks => "&DENYBADCLOCKS&";

    community_edition.!debug_port::
        port => "&COMMUNITYPORT&";
    community_edition.debug_port::
        port => "5310";

    ipv6_enabled::
        bindtointerface => "::";

}

#######################################################

&if(MANAGED_NODES_NAME)&
body runagent control
{
  enterprise_edition::
  # If using enterprise, the nodes to connect to are using default port
        hosts => {
          &MANAGED_NODES_NAME: {
          "&it&",}&
        };

  !enterprise_edition::
  # When using community, we must use the port &COMMUNITYPORT& to connect to nodes
        hosts => {
          &MANAGED_NODES_NAME: {
          "&it&:&COMMUNITYPORT&",}&
        };

    any::
        max_children => "25";

    community_edition.!debug_port::
        port => "&COMMUNITYPORT&";
    debug_port::
        port => "5310";
}
&endif&

body report_data_select rudder_data_select_host
# @brief Data to collect from remote hosts by default
#
# By convention variables and classes known to be internal, (having no
# reporting value) should be prefixed with an underscore. By default the policy
# framework explicitly excludes these variables and classes from collection.
{
    metatags_include       => { "inventory", "report" };
    metatags_exclude       => { "noreport" };
    monitoring_include     => { ".*" };
    promise_handle_exclude => { "noreport_.*" };
}

body report_data_select rudder_data_select_policy_hub
# @brief Data to collect from policy servers by default
#
# By convention variables and classes known to be internal, (having no
# reporting value) should be prefixed with an underscore. By default the policy
# framework explicitly excludes these variables and classes from collection.
{
    metatags_include       => { "inventory", "report" };
    metatags_exclude       => { "noreport" };
    monitoring_include     => { ".*" };
    promise_handle_exclude => { "noreport_.*" };
}
