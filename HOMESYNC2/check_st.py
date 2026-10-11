def formatHoursAndMinutes(totalSeconds):
    hours = totalSeconds // 3600
    minutes = (totalSeconds % 3600) // 60
    if hours > 0:
        return f"{hours}h {minutes}m"
    else:
        return f"{minutes}m"
print(formatHoursAndMinutes(1560))
