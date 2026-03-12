export namespace main {
	
	export class ActivityEntry {
	    time: string;
	    filename: string;
	    status: string;
	    detail?: string;
	
	    static createFrom(source: any = {}) {
	        return new ActivityEntry(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.time = source["time"];
	        this.filename = source["filename"];
	        this.status = source["status"];
	        this.detail = source["detail"];
	    }
	}
	export class Config {
	    serverUrl: string;
	    username: string;
	    password: string;
	    watchDir: string;
	    concurrency: number;
	    chunkSizeMB: number;
	    autoStart: boolean;
	
	    static createFrom(source: any = {}) {
	        return new Config(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.serverUrl = source["serverUrl"];
	        this.username = source["username"];
	        this.password = source["password"];
	        this.watchDir = source["watchDir"];
	        this.concurrency = source["concurrency"];
	        this.chunkSizeMB = source["chunkSizeMB"];
	        this.autoStart = source["autoStart"];
	    }
	}
	export class ConnectionResult {
	    ok: boolean;
	    message: string;
	
	    static createFrom(source: any = {}) {
	        return new ConnectionResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.message = source["message"];
	    }
	}

}

