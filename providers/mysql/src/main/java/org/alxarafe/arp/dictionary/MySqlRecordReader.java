package org.alxarafe.arp.dictionary;

public class MySqlRecordReader extends MySqlRecordReaderBase
{
    public MySqlRecordReader(Connections connections)
    {
        this(connections, 5);
    }

    public MySqlRecordReader(Connections connections, int timeoutSeconds)
    {
        super(connections, timeoutSeconds);
        mariaDb = false;
    }
}