// Test-only: categories is a Set; copy/sort only that field, preserve every other value.
exports.configSnapshot=config=>({...config,categories:[...config.categories].sort()});
