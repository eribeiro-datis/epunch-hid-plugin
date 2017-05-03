var hid = {
  connectToDevice: function(successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      'Hid',
      'connectToDevice'
    );
  }
};
module.exports = hid;
