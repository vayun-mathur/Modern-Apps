(function () {
    'use strict';

    var contentBinding = window.__SABR_WEBPO_CONTENT_BINDING;

    function stage(name, detail) {
        SabrPocBridge.onStage(name, String(detail));
    }

    stage('script_start', 'bindingLength=' + (contentBinding ? contentBinding.length : -1));
    console.info('[sabr-webpo] script start bindingLength=' +
        (contentBinding ? contentBinding.length : -1));

    function report(result) {
        console.info('[sabr-webpo] report ok=' + !!result.ok);
        SabrPocBridge.onResult(JSON.stringify(result));
    }

    function waitForClient(attempt) {
        var type = typeof window.top['havuokmhhs-0']?.bevasrs?.wpc;
        stage('client_poll', 'attempt=' + attempt + ' type=' + type);
        console.info('[sabr-webpo] client attempt=' + attempt + ' type=' + type);
        if (type === 'function') {
            return Promise.resolve();
        }
        if (attempt >= 10) {
            return Promise.reject(new Error('WebPoClient unavailable'));
        }
        return new Promise(function (resolve) {
            setTimeout(resolve, 1000);
        }).then(function () {
            return waitForClient(attempt + 1);
        });
    }

    function mint(attempt) {
        stage('mint_start', 'attempt=' + attempt);
        console.info('[sabr-webpo] mint attempt=' + attempt);
        return window.top['havuokmhhs-0'].bevasrs.wpc().then(function (client) {
            stage('client_resolved', 'attempt=' + attempt + ' mws=' + typeof client?.mws);
            console.info('[sabr-webpo] client resolved mws=' + typeof client?.mws);
            return client.mws({c: contentBinding, mc: false, me: false});
        }).catch(function (error) {
            stage('mint_error', 'attempt=' + attempt + ' error=' + String(error));
            console.warn('[sabr-webpo] mint error=' + String(error));
            if (String(error).indexOf('SDF:notready') >= 0 && attempt < 10) {
                return new Promise(function (resolve) {
                    setTimeout(resolve, 1000);
                }).then(function () {
                    return mint(attempt + 1);
                });
            }
            throw error;
        });
    }

    waitForClient(0).then(function () {
        return mint(0);
    }).then(function (poToken) {
        stage('mint_success', 'tokenLength=' + (poToken ? poToken.length : -1));
        console.info('[sabr-webpo] mint success tokenLength=' +
            (poToken ? poToken.length : -1));
        report({ok: true, poToken: poToken});
    }).catch(function (error) {
        stage('pipeline_error', String(error));
        console.error('[sabr-webpo] pipeline error=' + String(error));
        report({ok: false, error: String(error)});
    });
})();
