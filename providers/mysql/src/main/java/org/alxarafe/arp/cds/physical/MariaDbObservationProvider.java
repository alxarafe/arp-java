package org.alxarafe.arp.cds.physical;

import org.alxarafe.arp.dictionary.SqlRecordReader.Connections;

public final class MariaDbObservationProvider extends MySqlObservationProviderBase
{
    public MariaDbObservationProvider(
            Connections connections, String provider, String id,
            String schema, String table, int timeout)
    {
        super(connections, provider, id, schema, table, timeout);
        mariaDb = true;
    }
}